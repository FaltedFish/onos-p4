# SRv6 自动化验证脚本使用说明

本文说明如何使用 `./tools/verify_srv6.py` 对当前 ONOS + BMv2/Mininet
环境做端到端 SRv6 smoke test。

## 脚本用途

`./tools/verify_srv6.py` 会自动完成以下流程：

- 使用标准入口 `./env/create_onos.sh` 准备 ONOS。
- 使用标准入口 `./env/create_mininet.sh` 以 batch 模式启动 Mininet。
- 等待 ONOS 发现设备、主机和基础 SRv6 `mySid` flow。
- 在 `r1` 上通过 ONOS CLI 下发 SRv6 transit policy。
- 执行 IPv6 ping，并在关键链路上抓包。
- 解析 pcap，确认流量经过期望的 SRv6 路径。

脚本当前固定验证以下拓扑和策略：

```text
topology:          4-router ring, 1 host/router
devices:           r1, r2, r3, r4
h1 address:        2001:1:1::10
h4 address:        2001:4:1::10
r2 SID:            fc00:0:2::
r3 SID:            fc00:0:3::
policy on r1:      h4 via r2 SID, then r3 SID, then h4
```

对应 ONOS CLI 命令为：

```text
srv6-clear device:bmv2:r1
srv6-insert device:bmv2:r1 fc00:0:2:: fc00:0:3:: 2001:4:1::10
```

## 前置条件

运行前确认本机具备：

- Docker 可用，并能创建/删除 ONOS 容器。
- Mininet、BMv2 `simple_switch_grpc`、`tcpdump` 可用。
- ONOS REST 默认可通过 `http://127.0.0.1:8181` 访问。
- 当前目录是仓库根目录 `/home/p4/onos-ngsdn-app`。

如果当前用户执行 Mininet 需要 sudo，非交互运行时建议通过环境变量提供密码：

```bash
SUDO_PASSWORD='<sudo-password>' ./tools/verify_srv6.py
```

不要把真实 sudo 密码写入脚本、提交记录或文档。

## 快速运行

完整运行，包括构建/准备 ONOS 和 P4 产物：

```bash
SUDO_PASSWORD='<sudo-password>' ./tools/verify_srv6.py
```

如果刚刚已经执行过 `./build.sh` 或确认现有 OAR/P4 产物可复用，可以跳过构建：

```bash
./build.sh
SUDO_PASSWORD='<sudo-password>' ./tools/verify_srv6.py --skip-build
```

指定独立输出目录，便于保留多次实验结果：

```bash
SUDO_PASSWORD='<sudo-password>' ./tools/verify_srv6.py \
  --skip-build \
  --output-dir target/srv6-verify-run1
```

默认情况下，脚本会设置 `RECREATE_ONOS=1`，也就是每次重建 ONOS 容器，
避免旧 netcfg 或旧设备状态影响实验结果。

## 命令行参数

查看完整参数：

```bash
./tools/verify_srv6.py --help
```

常用参数：

```text
--output-dir DIR              保存日志、pcap 和 JSON 结果的目录
--skip-build                  复用已有 OAR/P4 产物，不让 create_onos.sh 重新构建
--keep-env                    早期失败时保留仍在运行的 Mininet，方便排查
--onos-timeout SECONDS        等待 ONOS 设备、主机、flow 收敛的时间
--mininet-ready-timeout SEC   等待 Mininet ready 文件的时间
--mininet-run-timeout SEC     等待 Mininet batch 流量测试结束的时间
--rest-timeout SECONDS        单次 ONOS REST 请求超时时间
```

示例：

```bash
SUDO_PASSWORD='<sudo-password>' ./tools/verify_srv6.py \
  --skip-build \
  --onos-timeout 360 \
  --mininet-ready-timeout 240 \
  --mininet-run-timeout 180
```

## 常用环境变量

脚本会设置固定验证拓扑：

```text
ROUTERS=4
HOSTS_PER_ROUTER=1
TOPOLOGY=ring
RECREATE_ONOS=1
MININET_NO_CLI=1
MININET_KEEP_RUNNING_AFTER_BATCH=1
```

可按需覆盖的环境变量：

```text
SUDO_PASSWORD          非交互 sudo 密码。设置后脚本会默认使用 SUDO_CMD="sudo -S"。
SUDO_PASSWORD_REPEAT   向 sudo stdin 预置密码的次数，默认 8。
SUDO_CMD               传给 create_mininet.sh 的 sudo 命令，例如 sudo、sudo -S。
RECREATE_ONOS          默认 1。设为 0 可复用当前 ONOS，但可能受旧状态影响。
ONOS_URL               默认 http://127.0.0.1:8181。
ONOS_AUTH              默认 onos:rocks。
ONOS_CONTAINER         默认 onos。
DOCKER_CMD             默认 docker。
NETCFG_IP              ONOS 访问 BMv2 gRPC 的地址，通常由 create_onos.sh 自动推导。
```

复用当前 ONOS 的调试运行示例：

```bash
RECREATE_ONOS=0 SUDO_PASSWORD='<sudo-password>' \
  ./tools/verify_srv6.py --skip-build
```

正常验证仍建议使用默认 `RECREATE_ONOS=1`。

## 输出文件

默认输出目录：

```text
target/srv6-verify
```

主要文件：

```text
result.json                         总结果，passed=true 表示通过
create_onos.log                     ./env/create_onos.sh 输出
create_mininet.log                  ./env/create_mininet.sh 和 Mininet 输出
mininet-batch.json                  传给 Mininet 的 batch 任务
mininet-result.json                 ping 和抓包任务结果
mininet-result.normalized.json      标准化后的 batch 结果
pcap-summary.json                   pcap 解析摘要和路径检查结果
r1-r2.pcap                          r1 -> r2 链路抓包
r2-r3.pcap                          r2 -> r3 链路抓包
r3-r4.pcap                          r3 -> r4 链路抓包
r1-r4-direct.pcap                   r1 -> r4 直连链路抓包
onos-before-srv6-insert-*.json      插入 SRv6 policy 前的 ONOS 快照
onos-after-srv6-insert-*.json       插入 SRv6 policy 后的 ONOS 快照
onos-failure-*.json                 失败时保存的 ONOS 快照
srv6-clear_*.log                    srv6-clear CLI 输出
srv6-insert_*.log                   srv6-insert CLI 输出
```

查看最终结果：

```bash
python3 -m json.tool target/srv6-verify/result.json
```

查看 pcap 路径判定：

```bash
python3 -m json.tool target/srv6-verify/pcap-summary.json
```

直接查看抓包：

```bash
tcpdump -nn -vv -r target/srv6-verify/r1-r2.pcap
tcpdump -nn -vv -r target/srv6-verify/r2-r3.pcap
tcpdump -nn -vv -r target/srv6-verify/r3-r4.pcap
```

## 通过标准

`result.json` 中应看到：

```json
{
  "passed": true,
  "checks": [
    "mininet-ready",
    "devices-available",
    "hosts-present",
    "srv6-my-sid-flows",
    "srv6-transit-flow",
    "ping-and-capture",
    "pcap-srv6-path"
  ]
}
```

`mininet-result.json` 中应看到：

- `h1-ping-h2-baseline` 成功，证明普通 IPv6 转发可用。
- `h1-ping-h4` 成功，证明 SRv6 policy 后的端到端通信可用。

`pcap-summary.json` 中应看到：

```text
r1-r2_srh_to_r2        true
r2-r3_srh_to_r3        true
r3-r4_popped_to_h4     true
r1-r4_no_direct_h1_to_h4 true
```

路径含义：

- `r1 -> r2`：目的地址为 `fc00:0:2::`，携带 SRH，`segments_left=2`。
- `r2 -> r3`：目的地址为 `fc00:0:3::`，携带 SRH，`segments_left=1`。
- `r3 -> r4`：`srv6_pop()` 后变回普通 ICMPv6，目的地址为 `2001:4:1::10`。
- `r1 -> r4`：不应出现从 `h1` 到 `h4` 的正向直连绕行流量。

## 验证完成后保留 Mininet

脚本以 batch 模式运行 Mininet。当前默认会设置
`MININET_KEEP_RUNNING_AFTER_BATCH=1`，因此验证完成并输出 PASS/FAIL 后，
Mininet 进程会继续在后台运行，BMv2 设备保持 `available`，ONOS live
拓扑中的 links 也会继续保留。

需要手动停止验证后保留的 Mininet 时，执行：

```bash
printf '<sudo-password>\n' | sudo -S pkill -f multi_router_p4runtime.py
printf '<sudo-password>\n' | sudo -S pkill -f simple_switch_grpc
printf '<sudo-password>\n' | sudo -S mn -c
```

也可以在交互终端中直接执行：

```bash
sudo pkill -f multi_router_p4runtime.py
sudo pkill -f simple_switch_grpc
sudo mn -c
```

如果 Mininet 被停止或异常退出，BMv2 设备会变为 `unavailable`。当前
`StaticNetcfgLinkProvider` 的设计是：

- 两端设备 available 时，将 netcfg 静态 link 发布到 ONOS `LinkService`。
- 设备 unavailable 后，撤销对应 link。

因此 Mininet 停止后再看 ONOS live 状态，`links=0` 是符合预期的。判断验证
期间拓扑是否正确，也可以查看脚本保存的快照：

```bash
python3 -m json.tool target/srv6-verify/onos-after-srv6-insert-links.json
python3 -m json.tool target/srv6-verify/onos-after-srv6-insert-devices.json
```

如果需要重新人工观察 ONOS Web Topology，可以手动启动匹配拓扑：

```bash
TOPOLOGY_FILE=target/env/topology-ring-4r-1h.json ./env/create_mininet.sh
```

## 常见失败排查

### Mininet ready file 没生成

常见原因：

- sudo 无法在非交互环境读取密码。
- `simple_switch_grpc` 不存在或不可执行。
- 旧 Mininet/BMv2 进程未清理干净。

处理方式：

```bash
SUDO_PASSWORD='<sudo-password>' ./tools/verify_srv6.py --skip-build
```

如果仍失败，先看：

```bash
tail -200 target/srv6-verify/create_mininet.log
```

### ONOS 设备数量不对

默认验证应只有 4 个 BMv2 设备。脚本默认 `RECREATE_ONOS=1`，用于清掉旧拓扑
残留。若手动设置了 `RECREATE_ONOS=0`，可能会看到旧设备。

处理方式：

```bash
SUDO_PASSWORD='<sudo-password>' ./tools/verify_srv6.py --skip-build
```

或显式：

```bash
RECREATE_ONOS=1 SUDO_PASSWORD='<sudo-password>' \
  ./tools/verify_srv6.py --skip-build
```

### 普通 IPv6 通、SRv6 不通

重点检查：

- `onos-after-srv6-insert-flows.json` 中是否有 `srv6_transit` flow。
- `r1` 是否有到 `fc00:0:2::` 的 `routing_v6_table` flow。
- `r2` 是否有 `srv6_my_sid` flow 匹配 `fc00:0:2::`。
- `r3` 是否有 `srv6_my_sid` flow 匹配 `fc00:0:3::`。

### ping 成功但 pcap 校验失败

先看摘要：

```bash
python3 -m json.tool target/srv6-verify/pcap-summary.json
```

再按链路查看 pcap：

```bash
tcpdump -nn -vv -r target/srv6-verify/r1-r2.pcap
tcpdump -nn -vv -r target/srv6-verify/r2-r3.pcap
tcpdump -nn -vv -r target/srv6-verify/r3-r4.pcap
```

当前 P4 行为是在最后一个 SID 处理后执行 `srv6_pop()`，所以 `r3-r4` 上期望
看到的是普通 IPv6 ICMPv6，而不是仍带 SRH 的包。
