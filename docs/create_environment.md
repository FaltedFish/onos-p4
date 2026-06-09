# ONOS 和 Mininet 环境入口脚本使用说明

本文说明如何使用 `./env/create_onos.sh`、`./env/create_mininet.sh`
和 `./env/clean_environment.sh` 管理本项目的 ONOS + BMv2/Mininet
端到端实验环境。

## 推荐流程

从仓库根目录执行：

```bash
cd /home/p4/onos-ngsdn-app

./env/create_onos.sh
./env/create_mininet.sh
```

两个脚本需要使用同一组拓扑参数。推荐先运行 `create_onos.sh`，
再按它最后打印的命令启动 `create_mininet.sh`。

默认拓扑为：

```text
ROUTERS=2
HOSTS_PER_ROUTER=1
TOPOLOGY=linear
```

启动后，在 Mininet CLI 中执行：

```text
pingall6
```

## create_onos.sh

`./env/create_onos.sh` 负责创建和配置 ONOS 环境入口。

主要动作：

- 创建或启动 Docker 中的 ONOS 2.7 容器。
- 确保 ONOS 容器没有占用 BMv2 gRPC 起始端口。
- 等待 ONOS REST API 可用。
- 默认先编译 P4，再构建 ONOS OAR。
- 激活 BMv2、gRPC、P4Runtime、LLDP、netcfg link/host provider 等基础应用。
- 安装并激活 `ngsdn-multirouter` OAR。
- 生成标准拓扑文件。
- 基于同一份拓扑文件生成 netcfg，并推送到 ONOS。

常用示例：

```bash
./env/create_onos.sh
```

重建 ONOS 容器，避免旧状态影响实验：

```bash
RECREATE_ONOS=1 ./env/create_onos.sh
```

复用已有 OAR，不重新构建 app：

```bash
BUILD_APP=0 ./env/create_onos.sh
```

构建 app 但跳过 P4 编译：

```bash
BUILD_P4=0 ./env/create_onos.sh
```

ONOS 不在默认 Docker bridge 网络中时，显式指定 BMv2 gRPC 可达地址：

```bash
NETCFG_IP=127.0.0.1 ONOS_REST_MODE=host ./env/create_onos.sh
```

## create_mininet.sh

`./env/create_mininet.sh` 负责清理并启动 Mininet 环境入口。

主要动作：

- 检查 BMv2 JSON 和 P4Info，缺失时默认执行 `./tools/build_p4.sh`。
- 生成或加载标准拓扑文件。
- 默认停止残留的 Mininet/P4Runtime 进程。
- 默认执行 `sudo mn -c` 清理上一次 Mininet 状态。
- 启动 `mininet/multi_router_p4runtime.py` 并进入 Mininet CLI。

常用示例：

```bash
./env/create_mininet.sh
```

不执行 `sudo mn -c` 清理：

```bash
CLEAN_MININET=0 ./env/create_mininet.sh
```

缺少 P4 产物时不自动构建：

```bash
BUILD_P4_IF_MISSING=0 ./env/create_mininet.sh
```

开启 pcap 抓包：

```bash
PCAP_DUMP=1 ./env/create_mininet.sh
```

指定 `simple_switch_grpc` 路径：

```bash
GRPC_EXE=/usr/local/bin/simple_switch_grpc ./env/create_mininet.sh
```

## clean_environment.sh

`./env/clean_environment.sh` 负责一键清空当前实验环境。

主要动作：

- 停止残留的 `multi_router_p4runtime.py` 和 `simple_switch_grpc` 进程。
- 执行 `sudo mn -c` 清理 Mininet 状态。
- 删除本项目 ONOS Docker 容器，默认包括 `onos`、`onos-*` 以及连接到
  `onos-ngsdn` 网络的容器。
- 删除本项目 ONOS Docker 网络 `onos-ngsdn`。

常用示例：

```bash
./env/clean_environment.sh
```

先打印将执行的命令，不实际清理：

```bash
DRY_RUN=1 ./env/clean_environment.sh
```

只清理 Mininet/BMv2，不删除 ONOS Docker：

```bash
CLEAN_ONOS_DOCKER=0 ./env/clean_environment.sh
```

只清理指定 ONOS 容器：

```bash
ONOS_CONTAINERS="onos-c1 onos-c2" CLEAN_ONOS_NETWORK=0 ./env/clean_environment.sh
```

## 拓扑参数

两个脚本共享以下拓扑参数。只要修改其中一个，ONOS 和 Mininet 两边都要使用
相同值。

```text
ROUTERS              路由器数量，默认 2
HOSTS_PER_ROUTER     每台路由器挂载主机数，默认 1
TOPOLOGY             linear、ring 或 mesh，默认 linear
TOPOLOGY_CONFIG      自定义拓扑输入 JSON
TOPOLOGY_FILE        已展开的标准拓扑 JSON
TOPOLOGY_OUTPUT_DIR  脚本自动生成拓扑文件的目录，默认 topologies/generated
GRPC_BASE            BMv2 gRPC 起始端口，默认 9559
THRIFT_PORT_BASE     BMv2 thrift 起始端口，默认 9090
DEVICE_ID_BASE       device ID 起始值，默认 0
CPU_PORT             BMv2 CPU port，默认 255
LINK_BW              Mininet 链路带宽，单位 Mbit/s
LINK_DELAY           Mininet 链路延迟，例如 5ms、100us 或 1s
```

四台路由器、每台两个主机、环形拓扑：

```bash
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_onos.sh
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_mininet.sh
```

指定链路带宽和延迟：

```bash
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring LINK_BW=10 LINK_DELAY=5ms \
  ./env/create_onos.sh

ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring LINK_BW=10 LINK_DELAY=5ms \
  ./env/create_mininet.sh
```

更稳妥的方式是使用 `create_onos.sh` 最后打印的 `TOPOLOGY_FILE=...`
命令启动 Mininet，例如：

```bash
TOPOLOGY_FILE=topologies/generated/topology-ring-4r-2h.json LINK_BW=10 LINK_DELAY=5ms \
  ./env/create_mininet.sh
```

## 自定义拓扑

使用手写拓扑配置：

```bash
TOPOLOGY_CONFIG=topologies/custom-lab.json ./env/create_onos.sh
TOPOLOGY_CONFIG=topologies/custom-lab.json ./env/create_mininet.sh
```

脚本会把配置展开为标准拓扑文件：

```text
topologies/generated/topology-custom-lab.json
```

如果已经提前生成标准拓扑文件，可以让两个脚本直接复用：

```bash
TOPOLOGY_FILE=topologies/generated/custom-lab-topology.json ./env/create_onos.sh
TOPOLOGY_FILE=topologies/generated/custom-lab-topology.json ./env/create_mininet.sh
```

`topologies/generated/` 不会被 `mvn clean` 删除，适合保存手工展开后要跨多个
脚本复用的拓扑文件。不要把这类文件放在 `target/env/` 下，否则
`create_onos.sh` 默认构建 app 时可能会通过 `mvn clean` 删除它。

显式设置 `TOPOLOGY_FILE` 时，脚本只加载该文件，不会覆盖它。

## 多域独立 ONOS 环境

本项目支持把同一个 Mininet/BMv2 数据面拆给多个独立 ONOS 控制器域管理。
拓扑 JSON 中 router 可以配置 `domain` 字段，未配置时默认为 `default`。

最小可运行示例见 `docs/multi_controller_minimal_example.md`。跨交换机、跨控制器域
转发机制见 `docs/multi_controller_cross_switch_forwarding.md`。

两域示例拓扑：

```text
topologies/linear-4r-2domain.json

c1: r1, r2
c2: r3, r4
```

三域跨中间域示例拓扑：

```text
topologies/linear-6r-3domain.json

c1: r1, r2
c2: r3, r4
c3: r5, r6
```

先展开拓扑：

```bash
./tools/build_topology.py \
  --config topologies/linear-4r-2domain.json \
  --output topologies/generated/topology-linear-4r-2domain.json
```

如果本机已有默认 `onos` 容器，且它仍加载默认 `9559` 起始端口的 netcfg，
建议为两域实验换一组 BMv2 端口，避免多个 ONOS 争用同一批 BMv2 router：

```bash
./tools/build_topology.py \
  --config topologies/linear-4r-2domain.json \
  --grpc-base 9659 \
  --thrift-base 9190 \
  --output topologies/generated/topology-linear-4r-2domain-test.json
```

分别启动两个 ONOS 域：

```bash
DOMAIN=c1 \
ONOS_CONTAINER=onos-c1 \
ONOS_REST_PORT=8281 \
RECREATE_ONOS=1 \
TOPOLOGY_FILE=topologies/generated/topology-linear-4r-2domain-test.json \
./env/create_onos.sh

DOMAIN=c2 \
ONOS_CONTAINER=onos-c2 \
ONOS_REST_PORT=8381 \
RECREATE_ONOS=1 \
TOPOLOGY_FILE=topologies/generated/topology-linear-4r-2domain-test.json \
./env/create_onos.sh
```

`DOMAIN` 会让 `build_netcfg.py --domain` 只生成本域 router、host 和域内 link。
跨域链路会生成应用配置 `domainBoundaryConfig`，由
`DomainBoundaryRoutingComponent` 自动在边界 router 下发直接邻域 router SID
`/128` 可达规则。非相邻域信息不会自动传播；跨中间域通信需要在入口 router
安装显式 SRv6 policy。

启动同一份 Mininet 拓扑：

```bash
TOPOLOGY_FILE=topologies/generated/topology-linear-4r-2domain-test.json \
./env/create_mininet.sh
```

检查两个 ONOS 的设备范围：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8281/onos/v1/network/configuration/devices

curl -sS --user onos:rocks \
  http://127.0.0.1:8381/onos/v1/network/configuration/devices
```

预期：

```text
onos-c1: device:bmv2:r1, device:bmv2:r2
onos-c2: device:bmv2:r3, device:bmv2:r4
```

三域实验的启动方式相同，只是为每个域各运行一次 `create_onos.sh`。例如：

```bash
./tools/build_topology.py \
  --config topologies/linear-6r-3domain.json \
  --grpc-base 9759 \
  --thrift-base 9290 \
  --output topologies/generated/topology-linear-6r-3domain-test.json

DOMAIN=c1 ONOS_CONTAINER=onos-c1 ONOS_REST_PORT=8281 RECREATE_ONOS=1 \
  TOPOLOGY_FILE=topologies/generated/topology-linear-6r-3domain-test.json \
  ./env/create_onos.sh

DOMAIN=c2 ONOS_CONTAINER=onos-c2 ONOS_REST_PORT=8381 RECREATE_ONOS=1 \
  TOPOLOGY_FILE=topologies/generated/topology-linear-6r-3domain-test.json \
  ./env/create_onos.sh

DOMAIN=c3 ONOS_CONTAINER=onos-c3 ONOS_REST_PORT=8481 RECREATE_ONOS=1 \
  TOPOLOGY_FILE=topologies/generated/topology-linear-6r-3domain-test.json \
  ./env/create_onos.sh

TOPOLOGY_FILE=topologies/generated/topology-linear-6r-3domain-test.json \
  ./env/create_mininet.sh
```

在这个拓扑中，`c1` 只生成到直接邻域 `c2` 的边界规则，`c3` 只生成到
直接邻域 `c2` 的边界规则，`c2` 分别生成到 `c1` 和 `c3` 的边界规则。
从 `h1` 到 `h6` 的跨中间域转发需要类似
`./tools/insert_srv6.py --clear r1 r2 r3 r4 r5 h6` 的 SRv6 policy。

## create_onos.sh 常用变量

```text
APP_NAME                 ONOS app 名称
ONOS_CONTAINER           ONOS Docker 容器名，默认 onos
ONOS_IMAGE               ONOS Docker 镜像，默认 hub.rat.dev/onosproject/onos:2.7.0
ONOS_URL                 宿主机访问 ONOS REST 的 URL，默认 http://127.0.0.1:8181
ONOS_INTERNAL_URL        container 模式下容器内访问的 REST URL
ONOS_AUTH                ONOS REST 认证，默认 onos:rocks
ONOS_REST_MODE           container 或 host，默认 container
ONOS_REST_PORT           宿主机 REST 映射端口，默认 8181
ONOS_SSH_PORT            宿主机 Karaf SSH 映射端口，默认随 ONOS_REST_PORT 偏移
ONOS_GRPC_PORT           宿主机 ONOS gRPC 映射端口，默认随 ONOS_REST_PORT 偏移
ONOS_OFCONFIG_PORT       宿主机 OF-CONFIG 映射端口，默认随 ONOS_REST_PORT 偏移
ONOS_OPENFLOW_PORT       宿主机 OpenFlow 映射端口，默认随 ONOS_REST_PORT 偏移
ONOS_TEST_PORT           宿主机 ONOS test port 映射端口，默认随 ONOS_REST_PORT 偏移
DOMAIN                   只生成并推送指定 router domain 的 netcfg
DOCKER_CMD               Docker 命令，默认 docker
ONOS_DOCKER_NETWORK      ONOS Docker bridge 网络，默认 onos-ngsdn
ONOS_DOCKER_SUBNET       Docker 网络网段，默认 172.20.0.0/16
ONOS_DOCKER_GATEWAY      Docker 网络网关，默认 172.20.0.1
NETCFG_IP                ONOS 访问 BMv2 gRPC 的地址
NETCFG_FILE              生成的 netcfg 文件路径
TOPOLOGY_OUTPUT_DIR      自动生成 topology 和 domain map 的目录，默认 topologies/generated
DOMAIN_MAP_FILE          多域运行时 domain map 文件路径
BUILD_APP                是否构建 ONOS app，默认 1
BUILD_P4                 构建 app 前是否编译 P4，默认 1
RECREATE_ONOS            是否重建 ONOS 容器，默认 0
RECREATE_ON_PORT_CONFLICT ONOS 占用 GRPC_BASE 时是否自动重建，默认 1
RECREATE_ONOS_NETWORK    是否重建 ONOS Docker 网络，默认 0
CLEAN_STALE_ONOS_NETWORK 是否自动清理异常 Docker 网络，默认 1
```

## create_mininet.sh 常用变量

```text
GRPC_EXE                    simple_switch_grpc 路径
JSON_PATH                   BMv2 JSON 路径
P4INFO_PATH                 P4Info 路径
ONOS_IP                     传给 Mininet 脚本的 ONOS IP，默认 127.0.0.1
CLEAN_MININET               是否执行 sudo mn -c，默认 1
CLEAN_STALE_P4              是否停止残留 P4Runtime/Mininet 进程，默认 1
BUILD_P4_IF_MISSING         P4 产物缺失时是否自动构建，默认 1
PCAP_DUMP                   是否启用 Mininet 脚本抓包，默认 0
SUDO_CMD                    sudo 命令，默认 sudo
MININET_NO_CLI              是否禁用交互 CLI，默认 0
MININET_BATCH_JSON          batch 任务 JSON 路径
MININET_BATCH_RESULT        batch 结果输出路径
MININET_KEEP_RUNNING_AFTER_BATCH batch 执行后是否保持 Mininet 运行
```

## clean_environment.sh 常用变量

```text
ONOS_CONTAINER           默认 ONOS Docker 容器名，默认 onos
ONOS_CONTAINERS          要删除的容器名列表，支持空格或逗号分隔
ONOS_DOCKER_NETWORK      要删除的 ONOS Docker 网络，默认 onos-ngsdn
DOCKER_CMD               Docker 命令，默认 docker
SUDO_CMD                 sudo 命令，默认 sudo
CLEAN_MININET            是否执行 Mininet/BMv2 清理，默认 1
CLEAN_STALE_P4           是否停止残留 P4Runtime/Mininet 进程，默认 1
CLEAN_ONOS_DOCKER        是否删除 ONOS Docker 容器，默认 1
CLEAN_ONOS_NETWORK       是否删除 ONOS Docker 网络，默认 1
REMOVE_ONOS_VOLUMES      删除容器时是否附带匿名 volume，默认 0
DRY_RUN                  只打印命令不执行，默认 0
```

## 生成文件

常见输出文件：

```text
topologies/generated/topology-*.json          标准拓扑文件
target/env/netcfg-*.json            推送到 ONOS 的 netcfg
topologies/generated/domain-map-*.json 多域运行时 domain map
target/ngsdn-multirouter-*.oar      ONOS app 安装包
p4build/ngsdn_tutorial/*            BMv2 JSON 等 P4 编译产物
p4build/ngsdn_tutorial.p4info.pb.txt P4Info
```

## 常见问题

ONOS 看不到设备：

- 确认先运行了 `./env/create_onos.sh`，再运行 `./env/create_mininet.sh`。
- 确认两个脚本使用同一份 `TOPOLOGY_FILE` 或同一组拓扑参数。
- 确认 `NETCFG_IP` 是 ONOS 容器可以访问到的宿主机地址。
- 确认 ONOS 容器没有占用 `GRPC_BASE` 对应端口。

Mininet 启动失败：

- 检查 `GRPC_EXE` 指向的 `simple_switch_grpc` 是否存在且可执行。
- 检查当前用户是否可以执行 `sudo mn -c`。
- 检查 P4 产物是否存在，或允许 `BUILD_P4_IF_MISSING=1` 自动构建。

跨路由器 IPv6 不通：

- 在 ONOS CLI 中检查 `devices`、`links`、`hosts`、`flows`、`groups`。
- 在 Mininet CLI 中执行 `pingall6`。
- 确认 ONOS netcfg 和 Mininet 来自同一份标准拓扑文件。
