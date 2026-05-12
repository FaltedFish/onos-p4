# 2026-05-03 iperf3 带宽测试与 Mininet offload 处理

## 背景问题

本次排查围绕当前 4 router、每 router 2 host 的 ring 拓扑进行：

```bash
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_onos.sh
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring LINK_BW=10 LINK_DELAY=5ms ./env/create_mininet.sh
```

现象：

- `h1 ping6 2001:2:1::10` 可以正常收到回包，说明 IPv6 路由和 NDP 基本可用。
- `h1 iperf3 -6 -c 2001:2:1::10 -t 20` 长时间没有输出，最终中断。
- 在 `h3 iperf3 -s -6 &` 后，`h1 iperf3 -6 -c 2001:2:1::10 --connect-timeout 3000` 报 `Connection timed out`。

地址确认：

- `2001:1:1::10` 是 `h1`。
- `2001:1:2::10` 是 `h2`。
- `2001:2:1::10` 是 `h3`。

因此测试 `h1 -> 2001:2:1::10` 时，iperf3 server 必须启动在 `h3`，不是 `h2`。

## 判断

`ping6` 正常但 iperf3 TCP 连接超时，说明问题不在基础 IPv6 转发，而更可能出现在 TCP 报文处理上。

在 BMv2/Mininet 环境中，常见原因是 host veth 网卡的 checksum 或 segmentation offload 没有完全关闭。此时 ICMPv6 可以正常工作，但 TCP SYN 或后续 TCP 报文可能因为 checksum/offload 表现异常而被对端内核丢弃。

另外需要注意：

- iperf3 的 UDP 模式也需要先建立 TCP 控制连接到 server 的 `5201` 端口。
- 所以 TCP 控制连接不通时，`iperf3 -u` 也可能同样失败，并不能直接证明 UDP 数据面不通。

## 改动内容

修改 `mininet/p4_mininet.py` 中 `P4Host.config()` 的 offload 关闭逻辑。

原逻辑只固定调用 `/sbin/ethtool`，并只关闭：

```text
rx tx sg
```

新逻辑：

- 自动查找 `ethtool` 路径：
  - `command -v ethtool`
  - `/sbin/ethtool`
  - `/usr/sbin/ethtool`
- 关闭更多和 TCP/UDP 测试相关的 offload：

```text
rx tx sg tso gso gro lro
```

- 对不支持关闭的 offload 项使用 `|| true`，避免例如 `lro` 不可修改时中断 host 配置。
- 如果系统没有安装 `ethtool`，输出警告，提示 TCP/UDP through BMv2 可能因为 checksum offload 失败。

## 涉及文件

- `mininet/p4_mininet.py`

## 验证方式

语法检查：

```bash
python3 -m py_compile mininet/p4_mininet.py mininet/multi_router_p4runtime.py
```

Mininet CLI 中推荐的连通性验证：

```bash
pingall6
h1 ping6 -c 3 2001:2:1::10
```

iperf3 TCP 测试：

```bash
h3 pkill iperf3
h3 iperf3 -s -6 &
h3 ss -ltnp
h1 iperf3 -6 -c 2001:2:1::10 -t 10 -i 1 --connect-timeout 3000
```

iperf3 UDP 测试，链路带宽设置为 `10Mbit/s` 时建议从低到高压测：

```bash
h1 iperf3 -6 -u -c 2001:2:1::10 -b 1M -l 1200 -t 10 -i 1
h1 iperf3 -6 -u -c 2001:2:1::10 -b 5M -l 1200 -t 10 -i 1
h1 iperf3 -6 -u -c 2001:2:1::10 -b 8M -l 1200 -t 10 -i 1
h1 iperf3 -6 -u -c 2001:2:1::10 -b 12M -l 1200 -t 10 -i 1
```

观察 UDP 输出中的：

- `Bitrate`
- `Jitter`
- `Lost/Total Datagrams`

当 `Lost/Total Datagrams` 明显升高时，说明发送速率超过当前路径可承载能力或队列开始丢包。

## 故障继续排查命令

如果重启后 TCP 仍然 timeout，先检查 offload 状态：

```bash
h1 ethtool -k eth0
h3 ethtool -k eth0
```

重点确认以下项为 `off`：

```text
rx-checksumming
tx-checksumming
scatter-gather
tcp-segmentation-offload
generic-segmentation-offload
generic-receive-offload
```

如需手动关闭：

```bash
h1 ethtool -K eth0 rx off tx off sg off tso off gso off gro off
h3 ethtool -K eth0 rx off tx off sg off tso off gso off gro off
```

如果 `lro` 报 `Cannot change large-receive-offload`，通常可以忽略，因为很多 veth 不支持修改该项。

检查 TCP SYN 是否到达 h3：

```bash
h3 tcpdump -i eth0 -n 'ip6 and tcp port 5201'
h1 iperf3 -6 -c 2001:2:1::10 -t 3 --connect-timeout 3000
```

判断：

- 能看到 SYN 但没有 SYN-ACK：问题更可能在 h3 server、h3 内核收包或 checksum。
- 看不到 SYN：问题更可能在 P4 转发路径、flow/group 或链路方向上。

## 最终验证结果

重启 ONOS 和 Mininet 后，iperf3 问题已确认修复。当前网络中 `h1 -> h3`
的 iperf3 测试可以正常执行，可继续使用 TCP 或 UDP iperf3 进行端到端
吞吐测试。
