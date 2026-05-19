# NG-SDN Multi-Router ONOS App

本项目是一个用于 BMv2 `simple_switch_grpc` 多路由器拓扑的 ONOS 2.7
控制平面应用，基于 NG-SDN tutorial 结构扩展而来，并内置对应的
Mininet/P4Runtime 启动脚本。

## 总体目标

目标是在 Mininet 多路由器 IPv6 网络中，实现任意主机之间可以互相
`ping6`：

```text
h1 ping6 -c 3 2001:2:1::10
pingall6
```

为达成这个目标，项目需要同时保证：

- P4 pipeline 可以处理 L2 转发、IPv6 路由、NDP 网关应答和 SRv6 表项。
- ONOS app 能注册 pipeconf，并向 BMv2 设备下发 P4Runtime flow/group。
- netcfg 能描述设备、端口、链路、主机 IP/MAC 和接口网关地址。
- Mininet 启动时使用同一份 P4 JSON/P4Info，并且 CPU port 固定为 `255`。

## 当前现状

已完成：

- P4 源码已迁移到本项目：[p4src/ngsdn_tutorial.p4](/home/p4/onos-ngsdn-app/p4src/ngsdn_tutorial.p4)。
- P4 编译脚本已迁移到本项目：[tools/build_p4.sh](/home/p4/onos-ngsdn-app/tools/build_p4.sh)。
- Mininet 脚本已迁移到本项目：[mininet/multi_router_p4runtime.py](/home/p4/onos-ngsdn-app/mininet/multi_router_p4runtime.py)。
- P4 编译产物默认生成到 `p4build/`，不会被 `mvn clean` 删除。
- ONOS app 的 `src/main/resources/bmv2.json` 和 `p4info.txt` 由本项目内 P4 源码生成。
- `build.sh` 默认使用本机 Maven，不再依赖 Docker Maven 镜像。
- `BUILD_P4=1 ./build.sh` 可以先编译 P4，再打包 ONOS OAR。
- 当前代码包含 L2 bridging、NDP reply、IPv6 host routing 和 SRv6 相关组件。
- 本地构建已验证通过，产物为 `target/ngsdn-multirouter-1.0-SNAPSHOT.oar`。

仍需运行环境验证：

- 将 OAR 安装并激活到 ONOS。
- 推送与 Mininet 拓扑一致的 netcfg。
- 启动 Mininet 后检查 ONOS 中 `devices`、`links`、`hosts`、`flows`、`groups`。
- 最终执行 `pingall6`，确认任意主机互通。

## 目录说明

```text
p4src/ngsdn_tutorial.p4              P4 源码，当前项目的 P4 源头
p4build/                             P4 编译输出，供 Mininet 使用
src/main/resources/bmv2.json         ONOS pipeconf 使用的 BMv2 JSON
src/main/resources/p4info.txt        ONOS pipeconf 使用的 P4Info
src/main/java/...                    ONOS 控制平面代码
tools/build_p4.sh                    P4 编译和资源同步脚本
tools/build_netcfg.py                netcfg 生成脚本
mininet/multi_router_p4runtime.py    Mininet 多路由器 P4Runtime 拓扑脚本
mininet/p4_mininet.py                Mininet P4 host/switch 辅助类
env/create_onos.sh                   创建/配置 ONOS 环境入口
env/create_mininet.sh                清理并启动 Mininet 环境入口
build.sh                             ONOS app 构建脚本
target/*.oar                         ONOS app 安装包
```

## 快速创建环境

推荐优先使用 `env/` 下的两个入口脚本。两个脚本使用同一组拓扑环境变量，
因此修改路由器数量、主机数量或拓扑类型时，两边要使用相同参数。
详细用法见 [docs/create_environment.md](docs/create_environment.md)。

默认两台路由器线性拓扑：

```bash
cd /home/p4/onos-ngsdn-app

./env/create_onos.sh
./env/create_mininet.sh
```

`create_onos.sh` 会执行：

- 创建或启动 Docker 中的 ONOS 2.7 容器。
- 如果已有 ONOS 容器占用了 BMv2 gRPC 起始端口 `9559`，自动重建容器释放该端口。
- 等待 ONOS REST API 可用。
- 编译 P4 和 ONOS app。
- 激活 BMv2、gRPC、P4Runtime、netcfg link/host provider 等基础应用。
- 安装并激活 `ngsdn-multirouter` OAR。
- 生成标准拓扑文件，并基于该文件生成、推送 netcfg。

`create_mininet.sh` 会执行：

- 检查 P4 JSON/P4Info，不存在时自动运行 `./tools/build_p4.sh`。
- 生成或加载标准拓扑文件。
- 先运行 `sudo mn -c` 清理上一次 Mininet 残留。
- 启动本项目内的 `mininet/multi_router_p4runtime.py` 并进入 Mininet CLI。

四台路由器、每台两个主机、环形拓扑示例：

```bash
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_onos.sh
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_mininet.sh
```

指定所有 Mininet 链路的带宽和延迟：

```bash
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring LINK_BW=10 LINK_DELAY=5ms ./env/create_mininet.sh
```

也可以用 JSON 自定义拓扑。手写配置只需要描述路由器、链路和主机挂载关系，
脚本会先展开成标准拓扑文件，再由 ONOS 和 Mininet 共同加载：

```json
{
  "version": 1,
  "name": "custom-lab",
  "routers": [
    { "name": "r1" },
    { "name": "r2" },
    { "name": "r3" }
  ],
  "links": [
    { "left": "r1", "right": "r2" },
    { "left": "r2", "right": "r3" },
    { "left": "r1", "right": "r3", "bw": 10, "delay": "5ms" }
  ],
  "hosts": [
    { "name": "h1", "router": "r1" },
    { "name": "h2", "router": "r1" },
    { "name": "h3", "router": "r2" },
    { "name": "h4", "router": "r3" }
  ]
}
```

使用自定义配置启动：

```bash
TOPOLOGY_CONFIG=topologies/custom-lab.json ./env/create_onos.sh
TOPOLOGY_CONFIG=topologies/custom-lab.json ./env/create_mininet.sh
```

自定义输入支持按需覆盖自动分配结果：

```text
routers[].deviceId
routers[].grpcPort
routers[].thriftPort
routers[].cpuPort
routers[].myStationMac
routers[].mySid
links[].leftPort
links[].rightPort
links[].bw
links[].delay
hosts[].routerPort
hosts[].mac
hosts[].ip
hosts[].gateway
```

如果想先生成并复用展开后的拓扑文件：

```bash
./tools/build_topology.py \
  --config topologies/custom-lab.json \
  --output target/env/custom-lab-topology.json

TOPOLOGY_FILE=target/env/custom-lab-topology.json ./env/create_onos.sh
TOPOLOGY_FILE=target/env/custom-lab-topology.json ./env/create_mininet.sh
```

常用参数：

```text
ROUTERS              路由器数量，默认 2
HOSTS_PER_ROUTER     每台路由器挂载主机数，默认 1
TOPOLOGY             linear、ring 或 mesh，默认 linear
TOPOLOGY_CONFIG      自定义拓扑输入 JSON，脚本会展开成标准拓扑文件
TOPOLOGY_FILE        已展开的标准拓扑 JSON；显式设置时脚本直接加载，不覆盖
LINK_BW              Mininet 链路带宽，单位 Mbit/s，例如 10
LINK_DELAY           Mininet 链路延迟，例如 5ms、100us 或 1s
NETCFG_IP            ONOS 访问 BMv2 gRPC 的地址，Docker ONOS 默认 172.20.0.1
ONOS_URL             ONOS REST 地址，默认 http://127.0.0.1:8181
ONOS_AUTH            ONOS REST 认证，默认 onos:rocks
ONOS_REST_MODE       REST 调用方式，默认 container，即通过 docker exec 在容器内调用
ONOS_DOCKER_NETWORK  ONOS 使用的 Docker bridge 网络，默认 onos-ngsdn
ONOS_DOCKER_SUBNET   默认 172.20.0.0/16
ONOS_DOCKER_GATEWAY  默认 172.20.0.1
RECREATE_ONOS        设为 1 时重建 ONOS 容器
RECREATE_ON_PORT_CONFLICT 设为 1 时检测到 9559 端口冲突会自动重建，默认 1
BUILD_APP            设为 0 时跳过 ONOS app 构建
BUILD_P4             create_onos.sh 构建 app 前是否先构建 P4，默认 1
CLEAN_MININET        create_mininet.sh 是否执行 sudo mn -c，默认 1
```

如果 ONOS 不是运行在 Docker bridge 网络中，例如 ONOS 直接运行在宿主机，
通常需要：

```bash
NETCFG_IP=127.0.0.1 ./env/create_onos.sh
```

## 环境要求

- ONOS 2.7
- BMv2 `simple_switch_grpc`
- `p4c`
- 本机 Maven
- Mininet
- Python 3

常用版本检查：

```bash
mvn -v
p4c --version
simple_switch_grpc --version
```

## 编译 P4

修改 `p4src/ngsdn_tutorial.p4` 后，重新生成 P4 产物：

```bash
cd /home/p4/onos-ngsdn-app
./tools/build_p4.sh
```

脚本实际执行的核心命令：

```bash
p4c --target bmv2 --arch v1model --std p4-16 \
  -I /usr/share/p4c/p4include \
  -o p4build/ngsdn_tutorial \
  --p4runtime-files p4build/ngsdn_tutorial.p4info.pb.txt \
  p4src/ngsdn_tutorial.p4
```

脚本会同步：

```text
p4build/ngsdn_tutorial/ngsdn_tutorial.json -> src/main/resources/bmv2.json
p4build/ngsdn_tutorial.p4info.pb.txt -> src/main/resources/p4info.txt
```

如果 `p4c` 或 include 目录不在默认位置：

```bash
P4C_CMD=/path/to/p4c P4_INCLUDE_DIR=/path/to/p4include ./tools/build_p4.sh
```

当前 P4 编译可能出现如下 warning，但不阻塞构建：

```text
Target does not support default_action for IngressPipeImpl.routing_v6_table
```

## 构建 ONOS App

默认使用本机 Maven：

```bash
cd /home/p4/onos-ngsdn-app
./build.sh
```

等价于：

```bash
mvn clean package -DskipTests
```

如果要先编译 P4，再打包 OAR：

```bash
BUILD_P4=1 ./build.sh
```

如果 Maven 不在 `PATH` 中：

```bash
MVN_CMD=/path/to/mvn ./build.sh
```

如确实需要旧的 Docker Maven 构建：

```bash
USE_DOCKER=1 ./build.sh
USE_DOCKER=1 DOCKER_CMD="sudo docker" ./build.sh
```

输出产物：

```text
target/ngsdn-multirouter-1.0-SNAPSHOT.oar
```

## 启动和配置 ONOS

下面命令默认 ONOS REST API 在 `127.0.0.1:8181`，账号密码为
`onos:rocks`：

```bash
ONOS_URL=http://127.0.0.1:8181
ONOS_AUTH=onos:rocks
```

激活基础应用：

```bash
curl --fail -sSL --user ${ONOS_AUTH} -X POST \
  ${ONOS_URL}/onos/v1/applications/org.onosproject.drivers.bmv2/active

curl --fail -sSL --user ${ONOS_AUTH} -X POST \
  ${ONOS_URL}/onos/v1/applications/org.onosproject.protocols.grpc/active

curl --fail -sSL --user ${ONOS_AUTH} -X POST \
  ${ONOS_URL}/onos/v1/applications/org.onosproject.protocols.p4runtime/active

curl --fail -sSL --user ${ONOS_AUTH} -X POST \
  ${ONOS_URL}/onos/v1/applications/org.onosproject.lldpprovider/active

curl --fail -sSL --user ${ONOS_AUTH} -X POST \
  ${ONOS_URL}/onos/v1/applications/org.onosproject.netcfglinksprovider/active

curl --fail -sSL --user ${ONOS_AUTH} -X POST \
  ${ONOS_URL}/onos/v1/applications/org.onosproject.netcfghostprovider/active
```

`netcfghostprovider` 用于加载 netcfg 中的静态 host IP 和 location 信息。

安装并激活本项目 OAR：

```bash
curl --fail -sSL --user ${ONOS_AUTH} \
  -X POST -H 'Content-Type:application/octet-stream' \
  "${ONOS_URL}/onos/v1/applications?activate=true" \
  --data-binary @target/ngsdn-multirouter-1.0-SNAPSHOT.oar
```

## 生成拓扑和 netcfg

ONOS netcfg 和 Mininet 都应加载同一份标准拓扑文件，避免两边分别推导
端口、主机位置或 gRPC/device ID 后出现不一致。

如果 ONOS 运行在 Docker bridge 网络中，`--ip` 不要使用 `127.0.0.1`，
需要使用 ONOS 容器可以访问到的宿主机地址，例如 `172.20.0.1` 或宿主机
网卡 IP。

两台路由器线性拓扑示例：

```bash
cd /home/p4/onos-ngsdn-app

./tools/build_topology.py \
  --routers 2 \
  --hosts-per-router 1 \
  --topology linear \
  --output target/env/topology-linear-2r-1h.json

./tools/build_netcfg.py \
  --ip 127.0.0.1 \
  --topology-file target/env/topology-linear-2r-1h.json \
  --output netcfg-linear-2.json

curl --fail -sSL --user ${ONOS_AUTH} \
  -X POST -H 'Content-Type:application/json' \
  ${ONOS_URL}/onos/v1/network/configuration \
  -d @netcfg-linear-2.json
```

如果 ONOS 在 Docker bridge 网络中，通常改为：

```bash
./tools/build_netcfg.py \
  --ip 172.20.0.1 \
  --topology-file target/env/topology-linear-2r-1h.json \
  --output netcfg-linear-2.json
```

## 启动 Mininet

Mininet 脚本位于本项目 `mininet/` 目录。脚本默认使用本项目生成的
`p4build/` P4 JSON/P4Info，因此一般不需要手动传 `--json` 和 `--p4info`。
如果显式传入 P4Info，必须使用 `.p4info.pb.txt`，不要使用 `.p4i`。
启动前先清理上一次 Mininet 运行可能留下的接口、交换机和进程残留。

```bash
cd /home/p4/onos-ngsdn-app

sudo mn -c

sudo python3 ./mininet/multi_router_p4runtime.py \
  --grpc-exe /usr/bin/simple_switch_grpc \
  --topology-file target/env/topology-linear-2r-1h.json \
  --link-bw 10 \
  --link-delay 5ms \
  --cpu-port 255
```

如需更多主机或路由器，先生成新的拓扑文件，再给 netcfg 和 Mininet 复用：

```bash
./tools/build_topology.py \
  --routers 4 \
  --hosts-per-router 2 \
  --topology ring \
  --output target/env/topology-ring-4r-2h.json

./tools/build_netcfg.py \
  --ip 127.0.0.1 \
  --topology-file target/env/topology-ring-4r-2h.json \
  --output netcfg-ring-4.json
```

```bash
sudo mn -c

sudo python3 ./mininet/multi_router_p4runtime.py \
  --grpc-exe /usr/bin/simple_switch_grpc \
  --topology-file target/env/topology-ring-4r-2h.json \
  --cpu-port 255
```

## 验证

在 ONOS CLI 中检查：

```text
apps -a
devices
ports
links
hosts
flows
groups
```

期望现象：

- 每个 BMv2 路由器设备为 `AVAILABLE`。
- ONOS 能看到 netcfg 中定义的 links 和 hosts。
- 每个设备有 `my_station_table`、`l2_exact_table`、`l2_ternary_table`、
  `ndp_reply_table`、`routing_v6_table` 相关 flow。
- 远端主机路由会通过 SELECT group 和下一跳 MAC 转发。

在 Mininet CLI 中检查：

```text
h1 ping6 -c 3 2001:2:1::10
pingall6
```

### SRv6 自动化验证

详细用法见 [docs/verify_srv6.md](docs/verify_srv6.md)。

SRv6 smoke test 可以用脚本自动完成 ONOS/Mininet 环境准备、SRv6 transit
规则下发、ping6、抓包和结果判定：

```bash
./tools/verify_srv6.py
```

默认测试 `4-router ring + 1 host/router`，在 `r1` 安装：

```text
srv6-insert device:bmv2:r1 fc00:0:2:: fc00:0:3:: 2001:4:1::10
```

脚本会验证：

- ONOS 中 4 台 BMv2 设备可用，并发现主机。
- 每台设备已有 `srv6_my_sid` flow。
- `r1` 已下发 `srv6_transit` / `srv6_t_insert_3` flow。
- `h1 ping6 -c 3 2001:4:1::10` 成功。
- 抓包确认请求流量从 `r1-r2`、`r2-r3`、`r3-r4` 经过，前两跳携带
  SRH，最后一跳执行 `srv6_pop()` 后变回普通 IPv6，并且不走 `r1-r4`
  直连链路。

结果和证据默认写入：

```text
target/srv6-verify/result.json
target/srv6-verify/mininet-result.json
target/srv6-verify/pcap-summary.json
target/srv6-verify/*.pcap
target/srv6-verify/onos-*.json
```

常用选项：

```bash
./tools/verify_srv6.py --skip-build
./tools/verify_srv6.py --output-dir target/srv6-verify-run1
```

验证完成后脚本默认保留 Mininet，便于继续查看 ONOS Web Topology 和 live
links；停止时执行 `sudo mn -c` 并清理相关 BMv2/Mininet 进程。

## 常见问题

ONOS 看不到设备：

- 检查 netcfg 的 `managementAddress` 是否是 ONOS 能访问的地址。
- 检查 Mininet 是否加载了和 netcfg 相同的 `TOPOLOGY_FILE`。
- 检查 Mininet 是否使用了 `--cpu-port 255`。

ONOS 看得到设备但没有 host：

- 确认 `org.onosproject.netcfghostprovider` 已激活。
- 确认 netcfg 中 `hosts` 的 MAC、IP、location 与 Mininet 主机一致。
- 确认 Mininet 和 netcfg 都来自同一份标准拓扑文件。

同交换机主机可通但跨路由器不通：

- 检查 ONOS 中是否有 `links`。
- 检查 `routing_v6_table` flow 和 SELECT group 是否下发。
- 检查每台设备的 `fabricDeviceConfig.myStationMac` 是否存在。

主机无法解析网关 NDP：

- 检查 `ndp_reply_table` 是否有网关 IPv6 地址对应的 flow。
- 检查 netcfg 中端口 interface IP 是否为 `2001:<router>:<host>::254/64`。

P4 改动后 Mininet 行为没有变化：

- 重新执行 `./tools/build_p4.sh`。
- 确认使用的是本项目 `mininet/multi_router_p4runtime.py`，或显式传入本项目
  `p4build/` 下的 `--json` 和 `--p4info`。
- 重新执行 `./build.sh` 并重新安装 OAR 到 ONOS。

## 推荐端到端流程

推荐优先使用 `env/` 下的两个环境入口脚本，确保 ONOS netcfg 和 Mininet 使用
同一份拓扑参数：

```bash
cd /home/p4/onos-ngsdn-app

./env/create_onos.sh
./env/create_mininet.sh
```

两个脚本分别负责：

- `env/create_onos.sh`：创建/配置 ONOS 环境入口。
- `env/create_mininet.sh`：清理并启动 Mininet 环境入口。

在 Mininet CLI 中执行：

```text
pingall6
```
