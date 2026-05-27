# 2026-05-19 当前工作区相对上次提交的变更

## 背景问题

本记录汇总当前工作区相对上次 Git 提交（`HEAD`）的全部主要变更。当前工作区
包含多类未提交内容：

- ONOS 和 Mininet 需要复用同一份标准拓扑文件，避免端口、主机位置、gRPC
  端口、device ID 等配置分别推导后不一致。
- SRv6 功能需要更完整的自动化验证，包括下发 transit 规则、执行 ping6、抓包
  和解析路径。
- BMv2/P4Runtime 环境没有原生端口统计，ONOS Topology 中链路流量显示容易缺失
  或抖动。
- ONOS 2.7 不允许空 `PiCriterion`，L2 unmatched catch-all 规则在 app 重装或设备
  事件触发时可能抛出 `Cannot build PI criterion with 0 field matches`。

## 解决方式

### 共享标准拓扑

新增并推广标准拓扑 JSON：

- `tools/build_topology.py` 可以从模板参数或自定义 JSON 展开标准拓扑。
- `tools/build_netcfg.py` 支持从 `--topology-file` 生成 ONOS netcfg。
- `mininet/multi_router_p4runtime.py` 支持从同一份 `--topology-file` 创建 Mininet
  路由器、链路和主机。
- `env/create_onos.sh` 和 `env/create_mininet.sh` 增加 `TOPOLOGY_FILE`、
  `TOPOLOGY_CONFIG` 流程，保持 ONOS 和 Mininet 输入一致。
- README 和 `docs/create_environment.md` 补充模板拓扑、自定义拓扑和复用已展开拓扑
  的用法。

### SRv6 验证

扩展 SRv6 runtime 验证能力：

- `Ipv6RoutingComponent` 为每台设备的 `mySid` 下发 IPv6 路由，使 SRv6 中间 SID
  能被正常转发到下一跳。
- `mininet/multi_router_p4runtime.py` 增加 batch mode、ready/start/result 文件、
  tcpdump 抓包和非交互运行能力。
- 新增 `tools/verify_srv6.py`，自动准备 ONOS/Mininet、下发 `srv6-insert`、
  执行 `ping6`、收集 pcap 和 ONOS REST 证据。
- 新增 `docs/verify_srv6.md` 说明自动化验证流程。

### BMv2 端口统计和 ONOS 流量显示

新增 `Bmv2PortStatisticsProvider`：

- 在 P4 pipeline 中使用 `port_ingress_counter` 和 `port_egress_counter` 维护
  per-port ingress/egress 统计。
- 通过 ONOS P4Runtime read API 读取 counter cell，并通过
  `DeviceProviderService.updatePortStatistics()` 发布标准端口统计。
- 不再依赖 flow direct counter、`StatisticStore` synthetic output flow 或对端
  egress 到本端 ingress 的镜像。

### L2 unmatched 规则兼容 ONOS 2.7

修复 `L2BridgingComponent` 的 unmatched catch-all：

- 将空 `PiCriterion` 改为 `hdr.ethernet.dst_addr` 的零掩码 ternary match。
- 避免 ONOS 2.7 构建空 PI criterion 时抛出异常，保证 app 重装后 L2 初始化能继续
  执行。

### 静态链路同步

`StaticNetcfgLinkProvider` 增加端口事件监听：

- 对 `PORT_ADDED`、`PORT_UPDATED`、`PORT_REMOVED` 也触发静态 netcfg link 同步。
- 减少设备端口更新后静态链路状态不同步的窗口。

## 涉及文件

主要源码和脚本：

- `src/main/java/org/onosproject/ngsdn/tutorial/Bmv2PortStatisticsProvider.java`
- `src/main/java/org/onosproject/ngsdn/tutorial/L2BridgingComponent.java`
- `src/main/java/org/onosproject/ngsdn/tutorial/Ipv6RoutingComponent.java`
- `src/main/java/org/onosproject/ngsdn/tutorial/StaticNetcfgLinkProvider.java`
- `tools/build_topology.py`
- `tools/build_netcfg.py`
- `tools/verify_srv6.py`
- `mininet/multi_router_p4runtime.py`
- `env/create_onos.sh`
- `env/create_mininet.sh`

文档和示例：

- `README.md`
- `docs/create_environment.md`
- `docs/verify_srv6.md`
- `topologies/topology-ring-10r-1h.json`
- `change-records/2026-05-18-shared-topology-json.md`
- `change-records/2026-05-19-current-worktree-since-head.md`

生成或运行产物：

- `target/ngsdn-multirouter-1.0-SNAPSHOT.jar`
- `target/ngsdn-multirouter-1.0-SNAPSHOT.oar`
- `target/classes/...`
- `target/env/topology-ring-4r-1h.json`
- `target/env/netcfg-ring-4r-1h.json`
- `target/env/netcfg-ring-4r-2h.json` 已在当前工作区删除
- `tools/__pycache__/...`

## 验证结果

已执行：

```bash
mvn -q -DskipTests package
```

结果：构建通过。构建过程中出现 Java illegal reflective access warning，属于当前
Maven/Error Prone 依赖在该 JDK 下的警告，不阻塞打包。

已将生成的 OAR 通过 ONOS REST 重新安装并激活：

```bash
curl -u onos:rocks -X DELETE \
  http://127.0.0.1:8181/onos/v1/applications/org.onosproject.ngsdn-multirouter

curl -u onos:rocks -X POST \
  -H 'Content-Type: application/octet-stream' \
  'http://127.0.0.1:8181/onos/v1/applications?activate=true' \
  --data-binary @target/ngsdn-multirouter-1.0-SNAPSHOT.oar
```

运行环境检查：

- ONOS 中当前 app 安装后 flow 状态为 `72 / 72 ADDED`。
- ONOS 日志未再出现新的 `Bmv2PortStatisticsProvider` 报错。
- ONOS 日志未再出现新的 `Cannot build PI criterion with 0 field matches`。
- `/onos/v1/statistics/ports` 可看到 `r1`、`r4` 相关端口 counter 非零并能累计。

观察到但未在本次处理的日志：

- app 卸载时可能出现 `Unable to DELETE PRE entry ... NOT_FOUND Multicast group does not exist`。
- ONOS 日志中仍可见 `Tools Driver not found`，该日志与本次 BMv2 统计和 L2
  criterion 修复无直接关系。

## 注意事项

- `Bmv2PortStatisticsProvider.java` 当前是未跟踪源码文件，提交时需要显式加入。
- `target/`、`__pycache__/` 等生成产物也处于工作区变更中，提交前需要决定是否保留。
- BMv2 端口统计改为基于 P4Runtime counter 轮询，刷新粒度不是实时网卡级统计。
