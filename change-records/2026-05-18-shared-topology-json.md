# 2026-05-18 ONOS 与 Mininet 共享标准拓扑文件

## 背景问题

此前 ONOS netcfg 和 Mininet 拓扑分别根据 `ROUTERS`、`HOSTS_PER_ROUTER`、
`TOPOLOGY`、gRPC 端口、device ID 等参数独立推导。

这种方式在简单线性、环形或 mesh 拓扑中可用，但当需要扩展到更多路由器、
自定义链路、指定端口、调整主机挂载位置或复用已生成拓扑时，容易出现两边
端口编号、主机位置、设备 ID 或 gRPC 端口不一致的问题。

## 解决方式

新增标准拓扑 JSON 作为 ONOS 和 Mininet 的共同输入：

- 新增 `tools/build_topology.py`，用于从模板参数或自定义 JSON 展开标准拓扑。
- 标准拓扑中显式记录 router、router link、host、端口、MAC、IPv6 地址、
  gateway、gRPC/Thrift 端口、device ID、CPU port、`myStationMac` 和 `mySid`。
- `tools/build_netcfg.py` 改为优先读取 `--topology-file` 生成 ONOS netcfg。
- `mininet/multi_router_p4runtime.py` 支持 `--topology-file`，按同一份拓扑文件
  创建交换机、链路、主机，并配置主机 IPv6 地址和默认路由。
- `env/create_onos.sh` 和 `env/create_mininet.sh` 自动生成或加载拓扑文件，并
  通过 `TOPOLOGY_FILE`、`TOPOLOGY_CONFIG` 保持 ONOS 与 Mininet 输入一致。
- README 增加自定义拓扑 JSON、标准拓扑文件复用和新启动参数说明。

同时保留旧参数入口：

- 未指定 `TOPOLOGY_FILE` 或 `TOPOLOGY_CONFIG` 时，仍可用 `ROUTERS`、
  `HOSTS_PER_ROUTER` 和 `TOPOLOGY` 生成 `linear`、`ring`、`mesh` 模板拓扑。
- 显式指定 `TOPOLOGY_FILE` 时，脚本只加载已有文件，不覆盖。
- 指定 `TOPOLOGY_CONFIG` 时，脚本先展开为标准拓扑，再生成 netcfg 或启动
  Mininet。

## 涉及文件

- `tools/build_topology.py`
- `tools/build_netcfg.py`
- `mininet/multi_router_p4runtime.py`
- `env/create_onos.sh`
- `env/create_mininet.sh`
- `README.md`
- `topologies/topology-ring-10r-1h.json`
- `target/env/netcfg-ring-10r-1h.json`

## 使用方式

模板拓扑：

```bash
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_onos.sh
TOPOLOGY_FILE=target/env/topology-ring-4r-2h.json ./env/create_mininet.sh
```

自定义输入：

```bash
TOPOLOGY_CONFIG=topologies/custom-lab.json ./env/create_onos.sh
TOPOLOGY_CONFIG=topologies/custom-lab.json ./env/create_mininet.sh
```

复用已展开拓扑：

```bash
./tools/build_topology.py \
  --routers 10 \
  --hosts-per-router 1 \
  --topology ring \
  --output topologies/topology-ring-10r-1h.json

TOPOLOGY_FILE=topologies/topology-ring-10r-1h.json ./env/create_onos.sh
TOPOLOGY_FILE=topologies/topology-ring-10r-1h.json ./env/create_mininet.sh
```

## 验证建议

基础生成验证：

```bash
./tools/build_topology.py \
  --routers 2 \
  --hosts-per-router 1 \
  --topology linear \
  --output /tmp/topology-linear-2r-1h.json

./tools/build_netcfg.py \
  --ip 127.0.0.1 \
  --topology-file /tmp/topology-linear-2r-1h.json \
  --output /tmp/netcfg-linear-2r-1h.json
```

运行环境验证：

```bash
ROUTERS=10 HOSTS_PER_ROUTER=1 TOPOLOGY=ring ./env/create_onos.sh
TOPOLOGY_FILE=target/env/topology-ring-10r-1h.json ./env/create_mininet.sh
```

在 ONOS 和 Mininet 中检查：

```text
devices
links
hosts
flows
groups
pingall6
```

