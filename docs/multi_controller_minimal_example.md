# 多控制器网络最小实践

本文记录一个最小的两控制器域示例：`onos-c1` 控制 `r1,r2`，
`onos-c2` 控制 `r3,r4`，同一个 Mininet/BMv2 数据面承载四台 router。

示例拓扑：

```text
h1 - r1 - r2 - r3 - r4 - h4
     c1        |        c2
```

## 前置条件

从仓库根目录执行：

```bash
cd /home/p4/onos-ngsdn-app
```

先构建 ONOS app：

```bash
./build.sh
```

如果本机已有默认 `onos` 容器，建议本示例使用非默认 BMv2 gRPC/Thrift
端口，避免旧控制器还尝试连接同一批 BMv2 设备。

## 1. 生成两域拓扑文件

先显式生成展开后的拓扑文件：

```bash
./tools/build_topology.py \
  --config topologies/linear-4r-2domain.json \
  --grpc-base 9659 \
  --thrift-base 9190 \
  --output target/env/topology-linear-4r-2domain-test.json
```

确认文件存在：

```bash
ls -l target/env/topology-linear-4r-2domain-test.json
```

如果后续 `create_mininet.sh` 报：

```text
Topology file not found: target/env/topology-linear-4r-2domain-test.json
```

说明这一步没有成功执行，或者当前终端不在仓库根目录。

## 2. 启动两个 ONOS 控制器

启动 `c1` 域：

```bash
DOMAIN=c1 \
ONOS_CONTAINER=onos-c1 \
ONOS_REST_PORT=8281 \
RECREATE_ONOS=1 \
TOPOLOGY_FILE=target/env/topology-linear-4r-2domain-test.json \
./env/create_onos.sh
```

启动 `c2` 域：

```bash
DOMAIN=c2 \
ONOS_CONTAINER=onos-c2 \
ONOS_REST_PORT=8381 \
RECREATE_ONOS=1 \
TOPOLOGY_FILE=target/env/topology-linear-4r-2domain-test.json \
./env/create_onos.sh
```

`DOMAIN` 会让脚本只为当前控制器生成并推送本域 netcfg。

## 3. 启动 Mininet 数据面

启动同一份 Mininet/BMv2 拓扑：

```bash
TOPOLOGY_FILE=target/env/topology-linear-4r-2domain-test.json \
./env/create_mininet.sh
```

这个命令会进入 Mininet CLI。保持该终端不要退出；退出后 BMv2 设备也会停止，
ONOS 中的设备和链路会消失。

## 4. 检查控制器是否看到设备

另开一个终端执行：

```bash
curl -sS --user onos:rocks http://127.0.0.1:8281/onos/v1/devices
curl -sS --user onos:rocks http://127.0.0.1:8381/onos/v1/devices
```

预期：

```text
onos-c1: device:bmv2:r1, device:bmv2:r2
onos-c2: device:bmv2:r3, device:bmv2:r4
```

如果两个接口都返回空设备，先确认 Mininet 是否还在运行：

```bash
pgrep -a -f 'simple_switch_grpc|mininet|multi_router_p4runtime.py'
```

也可以检查两个控制器是否已经收到本域 netcfg：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8281/onos/v1/network/configuration/devices

curl -sS --user onos:rocks \
  http://127.0.0.1:8381/onos/v1/network/configuration/devices
```

## 5. 下发跨域 SRv6 policy

准备 domain map：

```bash
mkdir -p target/2domain-runtime

cat > target/2domain-runtime/domains.json <<'EOF'
{
  "c1": {
    "ONOS_CONTAINER": "onos-c1"
  },
  "c2": {
    "ONOS_CONTAINER": "onos-c2"
  }
}
EOF
```

从 `h1` 到 `h4` 的入口是 `r1`，因此规则下发到 `onos-c1`：

```bash
./tools/insert_srv6.py \
  --topology-file target/env/topology-linear-4r-2domain-test.json \
  --domain-map target/2domain-runtime/domains.json \
  --clear r1 r2 r3 h4
```

反向从 `h4` 到 `h1` 的入口是 `r4`，因此规则下发到 `onos-c2`：

```bash
./tools/insert_srv6.py \
  --topology-file target/env/topology-linear-4r-2domain-test.json \
  --domain-map target/2domain-runtime/domains.json \
  --clear r4 r3 r2 h1
```

## 6. 验证端到端连通性

回到 Mininet CLI 执行：

```text
h1 ping6 -c 3 2001:4:1::10
h4 ping6 -c 3 2001:1:1::10
```

预期两个方向都是：

```text
0% packet loss
```

## 常见问题

`Topology file not found`：

- 先执行第 1 步生成 `target/env/topology-linear-4r-2domain-test.json`。
- 确认当前目录是 `/home/p4/onos-ngsdn-app`。
- ONOS 和 Mininet 必须使用同一个 `TOPOLOGY_FILE`。

ONOS UI 没有拓扑：

- 先用 REST API 检查 `/onos/v1/devices`，不要只看 UI。
- 确认 Mininet CLI 终端没有退出。
- 确认 `onos-c1` 和 `onos-c2` 收到的是本域 netcfg。

跨域 ping 不通：

- 先确认两个方向的 `insert_srv6.py` 都已执行。
- 确认 `--domain-map` 中容器名是 `onos-c1` 和 `onos-c2`。
- 确认 `h1` 的地址是 `2001:1:1::10`，`h4` 的地址是
  `2001:4:1::10`。
