# 2026-05-27 BMv2 流量上报逻辑梳理

## 背景问题

BMv2 `simple_switch_grpc` 在当前 ONOS 2.7/P4Runtime 实验环境中没有直接向
ONOS 暴露可用的原生端口统计。因此 ONOS Topology 和 REST statistics 接口默认拿不到
链路负载，无法直观看到多路由拓扑里的链路流量。

当前实现通过 `Bmv2PortStatisticsProvider` 做一层合成统计：从 P4 表项 direct
counter 读取每条 flow 的 packet/byte counter，再按 flow 的输出端口汇总为端口
egress 统计，并补齐 ONOS 统计服务需要的 flow/port 视图。

## 当前实现路径

### 1. P4 侧提供 counter 来源

`p4src/ngsdn_tutorial.p4` 为多张 ingress 表定义了 `direct_counter`：

- `l2_exact_table_counter`
- `l2_ternary_table_counter`
- `ndp_reply_table_counter`
- `my_station_table_counter`
- `routing_v6_table_counter`
- `srv6_my_sid_table_counter`
- `srv6_transit_table_counter`
- `acl_table_counter`

当前端口流量上报只使用 `IngressPipeImpl.l2_exact_table` 的 direct counter。
原因是 host 和 next-hop 的二层转发表项最终都会执行
`IngressPipeImpl.set_egress_port(port_num)`，可以从 flow treatment 里解析出实际
egress port。

这些 `l2_exact_table` flow 主要由两处下发：

- `L2BridgingComponent.learnHost()`：为本地 host MAC 下发 L2 unicast flow。
- `Ipv6RoutingComponent.createL2NextHopRule()`：为 L3 next-hop MAC 下发 L2
  next-hop flow。

### 2. Provider 注册和轮询

`Bmv2PortStatisticsProvider` 是一个 OSGi immediate component，同时实现
`DeviceProvider`。

启动后它会：

- 使用 `DeviceProviderRegistry.register(this)` 注册为辅助 device provider。
- 延迟 `INITIAL_SETUP_DELAY` 秒后开始轮询。
- 之后每 `POLL_INTERVAL_SECONDS = 2` 秒轮询一次。
- 只处理本节点是 master 且 `DeviceService` 认为 available 的设备。

端口集合来自三类来源：

- netcfg 中的静态 `LinkKey` 端点。
- netcfg 中的 `ConnectPoint` 端点。
- ONOS 当前已发现且 enabled 的 device ports。

CPU port `255` 会被排除，剩余端口通过
`DeviceProviderService.updatePorts()` 以 packet port、10G speed 形式发布，保证
ONOS 统计和拓扑视图能看到这些端口。

### 3. 从 flow counter 推导 egress 端口统计

每次轮询设备时，Provider 会遍历 `FlowRuleService.getFlowEntries(deviceId)`：

- 只接受 `ADDED` 状态的 flow。
- 只接受当前 app 下发的 flow。
- 只接受表 ID 为 `IngressPipeImpl.l2_exact_table` 的 flow。
- 只接受 treatment 中 PI action 为 `IngressPipeImpl.set_egress_port` 的 flow。
- 从 action 参数 `port_num` 解析出 egress port。
- 忽略不在已知端口集合里的端口。

随后使用 `FlowCounterState` 维护每条 flow 的 counter 状态：

- `rawPackets/rawBytes`：上一次看到的 BMv2/ONOS 原始 flow counter。
- `syntheticPackets/syntheticBytes`：累计后发布给 ONOS statistic store 的合成值。
- 每轮用当前原始 counter 减去上次原始 counter 得到 delta。
- 如果当前值小于上次值，按 counter 重置处理，delta 使用当前值。
- 累加时通过 `addCounter()` 做 `Long.MAX_VALUE` 饱和保护。

端口 egress 统计不再直接用“本轮所有 flow 原始 counter 总和”，而是保存
`syntheticPortCounters`，每轮只把 flow delta 加到对应端口的累计 sent counter。
这样可以避免 ONOS 同一份 counter 快照被重复发布或 counter 重置后导致速率窗口异常。

### 4. ingress 统计由对端 egress 映射

BMv2 当前没有直接可用的 per-port ingress counter。Provider 使用链路的反向关系合成
ingress：

- 对本设备端口 `A -> B`，读取对端设备端口 `B` 的 synthetic egress sent counter。
- 将对端的 `packetsSent/bytesSent` 写成本端的
  `packetsReceived/bytesReceived`。
- 链路来源同时考虑静态 netcfg `LinkKey` 和 ONOS `LinkService.getDeviceEgressLinks()`。

因此当前 ingress 值本质上是“链路对端 egress 的镜像”，不是 BMv2 端口硬件 ingress
counter。

### 5. 向 ONOS 发布端口统计

每轮生成 `DefaultPortStatistics`：

- `packetsSent/bytesSent` 来自本端累计 egress。
- `packetsReceived/bytesReceived` 来自对端累计 egress 镜像。

然后调用 `DeviceProviderService.updatePortStatistics(deviceId, portStatistics)` 更新
ONOS device statistics。ONOS REST `/onos/v1/statistics/ports` 可以看到这些端口统计。

### 6. 为 ONOS link load 准备 synthetic output flow

ONOS link load 计算依赖 statistic store 中带 output treatment 的 flow 统计。P4Runtime
flow 的原始 treatment 是 PI action，不是 ONOS 通用 `OUTPUT` instruction，因此 Provider
为每条可统计的 `l2_exact_table` flow 构造一条 synthetic flow rule：

- device、table、selector、priority、timeout 继承原始 flow。
- treatment 改为 `DefaultTrafficTreatment.builder().setOutput(portNumber)`。
- appId 使用当前 app。

Provider 对 synthetic rule 做生命周期管理：

- 新 flow：`statisticStore.prepareForStatistics(syntheticRule)`。
- 规则形态变化：先 `removeFromStatistics(previousRule)`，再 prepare 新规则。
- flow 消失：`removeFromStatistics()` 并清理本地 counter state。
- 每轮调用 `statisticStore.addOrUpdateStatistic(new DefaultFlowEntry(...))` 写入合成
  packet/byte counter。

当前代码还通过 `stableSyntheticStatisticRule()` 复用 exact match 不变的 synthetic
rule，减少重复 remove/prepare。

## 本次相对 HEAD 的改动点

当前工作区的 `Bmv2PortStatisticsProvider.java` 相对 `HEAD` 做了以下修正：

- 将 `publishedFlowLastSeen` 替换为 `flowCounterStates`，不再只用 `lastSeen` 判断是否
  重复发布，而是按原始 packet/byte counter 计算 delta。
- 将 `publishedPortStats` 替换为 `syntheticPortCounters`，端口统计变为单调累计的合成
  counter。
- `pollDevice()` 增加对 `getFlowEntries()` 的异常保护，读取失败时跳过本轮设备统计，
  避免一次异常中断整个轮询。
- 每轮都会调用 `updatePortStatistics()` 发布当前累计端口统计，不再依赖
  `MutablePortStats.equals()` 判断是否变化。
- `copyPeerEgressToIngress()` 改为读取缓存的 synthetic egress counter，不再现场遍历
  对端 `FlowRuleService.getFlowEntries()`。
- synthetic flow statistics 使用 `FlowCounterState.syntheticPackets/syntheticBytes`
  发布，避免 ONOS statistic store 收到重复原始快照或重置后的非单调数据。
- 清理 stale flow 或组件 deactivate 时同步清理 `flowCounterStates` 和
  `syntheticPortCounters`。

## 涉及文件

- `src/main/java/org/onosproject/ngsdn/tutorial/Bmv2PortStatisticsProvider.java`
- `p4src/ngsdn_tutorial.p4`
- `src/main/java/org/onosproject/ngsdn/tutorial/L2BridgingComponent.java`
- `src/main/java/org/onosproject/ngsdn/tutorial/Ipv6RoutingComponent.java`
- `src/main/java/org/onosproject/ngsdn/tutorial/common/Utils.java`
- `change-records/2026-05-27-bmv2-traffic-reporting.md`

## 验证方式

建议验证命令：

```bash
mvn test
```

运行环境验证：

```bash
./build.sh
./env/create_onos.sh
./env/create_mininet.sh
```

ONOS/Mininet 中重点检查：

```text
devices
links
hosts
flows
groups
pingall6
```

REST 检查：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8181/onos/v1/statistics/ports
```

期望结果：

- BMv2 设备端口存在 statistics。
- 有业务流量时相关端口 `packetsSent/bytesSent` 能累计增长。
- 链路另一侧端口的 `packetsReceived/bytesReceived` 能随对端 egress 同步增长。
- ONOS Topology/link load 能基于 synthetic output flow 显示链路负载。

## 注意事项

- 当前上报粒度由 2 秒轮询决定，不是实时硬件端口采样。
- 当前只从 `l2_exact_table` 推导端口流量，广播/组播、ACL clone/drop、NDP reply、
  SRv6 transit/my_sid 等表的 counter 没有计入端口 egress 汇总。
- ingress 是根据对端 egress 镜像合成，链路不完整或对端不在本节点可轮询范围内时，
  ingress 可能为空或滞后。
- `target/` 下 jar、oar、class 等生成产物当前也有工作区变更，提交前需要单独决定是否
  纳入版本控制。
