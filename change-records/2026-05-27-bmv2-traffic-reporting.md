# 2026-05-27 BMv2 端口统计改为 P4Runtime Counter

## 背景

BMv2 `simple_switch_grpc` 在当前 ONOS 2.7/P4Runtime 实验环境中没有直接向
ONOS 暴露可用的原生端口统计。之前的实现从 `l2_exact_table` flow direct counter
推导端口流量，并写入 ONOS `StatisticStore` synthetic output flow；该方式无法覆盖
广播、组播、NDP reply、ACL clone/drop 和 SRv6 等不经过 `l2_exact_table` 的流量。

## 当前实现

`p4src/ngsdn_tutorial.p4` 新增两个 indirect counter：

- `IngressPipeImpl.port_ingress_counter`：在 ingress pipeline 起始处按
  `standard_metadata.ingress_port` 计数。
- `EgressPipeImpl.port_egress_counter`：在 egress pipeline 按
  `standard_metadata.egress_port` 计数，覆盖组播复制后的实际出端口。

两个 counter 的 size 都是 `512`，覆盖 v1model `bit<9>` 端口空间。Java 侧继续排除
CPU port `255`，不会向 ONOS 标准端口统计发布 CPU 端口。

`Bmv2PortStatisticsProvider` 保留为辅助 `DeviceProvider`：

- 继续从 netcfg link、interface connect point 和已发现 device port 汇总端口集合。
- 继续通过 `DeviceProviderService.updatePorts()` 发布端口描述。
- 通过 `BasicDeviceConfig.managementAddress()` 解析 `device_id`，并使用
  `P4RuntimeClient.read(p4DeviceId, pipeconf).counterCells(...)` 读取两个 counter。
- 将 ingress counter 写入 `packetsReceived/bytesReceived`，将 egress counter 写入
  `packetsSent/bytesSent`。
- 通过 `DeviceProviderService.updatePortStatistics()` 上报 ONOS 标准
  `PortStatistics`。

旧的 flow-counter 合成逻辑已删除：

- 不再依赖 `StatisticStore`。
- 不再遍历 `FlowRuleService.getFlowEntries()`。
- 不再从 flow treatment 推导输出端口。
- 不再维护 synthetic flow、flow counter delta 或对端 egress 到本端 ingress 的镜像。

## 涉及文件

- `p4src/ngsdn_tutorial.p4`
- `src/main/java/org/onosproject/ngsdn/tutorial/Bmv2PortStatisticsProvider.java`
- `p4build/ngsdn_tutorial.p4info.pb.txt`
- `p4build/ngsdn_tutorial/ngsdn_tutorial.json`
- `src/main/resources/p4info.txt`
- `src/main/resources/bmv2.json`

## 验证方式

已执行：

```bash
./tools/build_p4.sh
mvn test
```

运行环境建议继续验证：

```bash
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

有业务流量时，相关端口的 `packetsReceived/bytesReceived` 和
`packetsSent/bytesSent` 应随实际 ingress/egress 流量增长。

## 注意事项

- 当前上报粒度由 2 秒轮询决定，不是实时硬件端口采样。
- ONOS Topology link load 如仍依赖 flow statistic store 中的 output treatment，
  可能不会自动消费标准 port statistics；本次变更的标准上报面是
  `/onos/v1/statistics/ports` 和 ONOS device statistics。
- 运行环境验证需要 ONOS 和 Mininet 变量保持一致，例如 `ROUTERS`、
  `HOSTS_PER_ROUTER`、`TOPOLOGY`、`NETCFG_IP`。
