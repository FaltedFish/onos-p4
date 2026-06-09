# 多控制器跨交换机通信机制说明

本文解释当前项目中 BMv2/P4Runtime router 和主机如何实现跨交换机、跨控制器域的 IPv6 通信。先区分两个容易混淆的场景：

- 单 ONOS 全拓扑模式下，如果没有执行 `srv6-insert`，`h1 -> h4` 仍然能 ping 通，那么这条流量走的是普通 IPv6 路由，包里没有 SRH。
- 多 ONOS 域模式下，跨域 host `/128` 不会自动发布到外域；跨域通信需要入口 router 安装 SRv6 policy。
- SRv6 是可选的显式路径策略。只有执行 `srv6-insert` 后，入口 router 才会给匹配目的地址的包插入 SRH。
- 普通 IPv6 能转发，是因为 ONOS 根据 netcfg 中的主机、链路、router MAC 等信息，提前给每台 router 下发了 `routing_v6_table` 和 `l2_exact_table` 表项。

## 1. 网络分层

以四台 router 线性拓扑为例：

```text
h1 - r1 - r2 - r3 - r4 - h4
```

每台 router 有：

- `myStationMac`: 这台 router 的三层网关 MAC。
- `mySid`: SRv6 场景使用的 SID；普通 IPv6 直接 ping 不依赖它。

启动 ONOS 时，`tools/build_netcfg.py` 会生成 netcfg。默认不设置 `DOMAIN` 时，netcfg 包含全拓扑：所有 router、host、link、接口网关地址。此时一个 ONOS 能看到完整拓扑，`Ipv6RoutingComponent` 会在每台 router 上安装到所有 host 的 `/128` 路由。

如果设置 `DOMAIN=c1/c2` 拆成两个独立 ONOS 控制域，单个 ONOS 只收到本域设备和本域 host。跨域链路处还会生成 `domainBoundaryConfig`，用于在边界 router 上安装直接邻域 SID 前缀的静态路由。

## 2. 主机发包时知道什么

以 `h1 ping6 2001:4:1::10` 为例，`h1` 一开始只知道：

```text
h1 IPv6:          2001:1:1::10/64
h1 MAC:           h1.mac
default gateway:  2001:1:1::254
```

`h1` 不知道：

- `h4` 的 MAC。
- `h4` 挂在 `r4` 上。
- 中间要经过 `r2/r3`。
- 当前网络有几个控制器。

`h1` 发现目的 `2001:4:1::10` 不在本地 `/64`，所以把包交给默认网关 `2001:1:1::254`。

## 3. NDP 解析默认网关

真正发 ICMPv6 Echo Request 前，`h1` 要先解析默认网关的 MAC。它发出 NDP Neighbor Solicitation：

```text
Ethernet:
  src = h1.mac
  dst = IPv6 solicited-node multicast MAC
IPv6:
  src = 2001:1:1::10
  dst = solicited-node multicast address
ICMPv6 NDP:
  type = Neighbor Solicitation
  target = 2001:1:1::254
```

`r1` 本地知道：

- `myStationMac = r1.myStationMac`
- host-facing 接口地址包含 `2001:1:1::254/64`
- `NdpReplyComponent` 已经下发：
  `ndp_reply_table[target=2001:1:1::254] -> ndp_ns_to_na(r1.myStationMac)`

因此 `r1` 直接在数据面回复 Neighbor Advertisement，告诉 `h1`：

```text
2001:1:1::254 对应 r1.myStationMac
```

注意，`h1` 解析的是网关 MAC，不是 `h4` 的 MAC。

## 4. 无 SRv6 时的真正数据包

NDP 完成后，`h1` 发出真正的 ICMPv6 包：

```text
Ethernet:
  src = h1.mac
  dst = r1.myStationMac
  etherType = IPv6
IPv6:
  src = 2001:1:1::10
  dst = 2001:4:1::10
  next_hdr = ICMPv6
  hop_limit = 64
ICMPv6:
  type = Echo Request
```

这个包里没有完整路径，也没有 SRH。沿途每台 router 都只做一次本地查表决策。

## 5. 普通 IPv6 逐跳处理

下面描述默认全拓扑 netcfg 场景，也就是你说的“没有插入 SRv6，直接启动网络也能 `h1 -> h4`”。

### 5.1 r1 处理

`r1` 收到包时，包头关键字段是：

```text
Ethernet dst = r1.myStationMac
IPv6 dst     = 2001:4:1::10
```

`r1` 本地知道：

- `my_station_table`: `r1.myStationMac -> NoAction`
- `routing_v6_table`: `2001:4:1::10/128 -> nextHopMac=r2.myStationMac`
- `l2_exact_table`: `r2.myStationMac -> r1-r2 端口`

这些表项来自 `Ipv6RoutingComponent`。它看到 ONOS host store 里有 `h4`，并根据 ONOS link graph 算出从 `r1` 到 `h4` 的第一跳是 `r2`。

`r1` 处理结果：

```text
Ethernet:
  src = r1.myStationMac
  dst = r2.myStationMac
IPv6:
  src = 2001:1:1::10
  dst = 2001:4:1::10
  hop_limit = 63
```

IPv6 目的地址不变，只有二层下一跳 MAC 改了。

### 5.2 r2 处理

`r2` 收到包时：

```text
Ethernet dst = r2.myStationMac
IPv6 dst     = 2001:4:1::10
```

`r2` 本地知道：

- `my_station_table`: `r2.myStationMac -> NoAction`
- `routing_v6_table`: `2001:4:1::10/128 -> nextHopMac=r3.myStationMac`
- `l2_exact_table`: `r3.myStationMac -> r2-r3 端口`

`r2` 处理结果：

```text
Ethernet:
  src = r2.myStationMac
  dst = r3.myStationMac
IPv6:
  src = 2001:1:1::10
  dst = 2001:4:1::10
  hop_limit = 62
```

### 5.3 r3 处理

`r3` 本地知道：

- `routing_v6_table`: `2001:4:1::10/128 -> nextHopMac=r4.myStationMac`
- `l2_exact_table`: `r4.myStationMac -> r3-r4 端口`

`r3` 处理结果：

```text
Ethernet:
  src = r3.myStationMac
  dst = r4.myStationMac
IPv6:
  src = 2001:1:1::10
  dst = 2001:4:1::10
  hop_limit = 61
```

### 5.4 r4 交付给 h4

`r4` 本地知道 `h4` 是直接连接的 host：

```text
routing_v6_table: 2001:4:1::10/128 -> nextHopMac=h4.mac
l2_exact_table:   h4.mac -> h4 端口
```

`r4` 发给 `h4` 的包：

```text
Ethernet:
  src = r4.myStationMac
  dst = h4.mac
IPv6:
  src = 2001:1:1::10
  dst = 2001:4:1::10
  hop_limit = 60
ICMPv6:
  type = Echo Request
```

到这里，`h4` 收到的是普通 ICMPv6 包。全程没有 SRH。

## 6. 为什么不同交换机知道往哪发

关键点是：不是主机知道，也不是包里携带完整路径，而是每台 router 本地已经被 ONOS 下发了表项。

普通 IPv6 下，每一跳只需要两类表项：

```text
routing_v6_table:
  IPv6 目的前缀 -> 下一跳 MAC

l2_exact_table:
  下一跳 MAC -> 出端口
```

例如 `r1` 不需要知道 `h4.mac`，只需要知道：

```text
2001:4:1::10/128 -> r2.myStationMac
r2.myStationMac  -> r1-r2 端口
```

`r4` 才需要知道最终 host 的 MAC：

```text
2001:4:1::10/128 -> h4.mac
h4.mac            -> h4 端口
```

这就是跨交换机通信能成立的原因。

## 7. 多控制器域里的区别

如果不设置 `DOMAIN`，一个 ONOS 收到全拓扑 netcfg，所有 router 都会安装到所有 host 的普通 IPv6 `/128` 路由。因此 `h1 -> h4` 不插入 SRv6 也能通。

如果设置：

```bash
DOMAIN=c1 ... ./env/create_onos.sh
DOMAIN=c2 ... ./env/create_onos.sh
```

那么每个 ONOS 只收到本域 netcfg：

```text
c1: r1, r2, h1, h2
c2: r3, r4, h3, h4
```

这时：

- `onos-c1` 正常不知道 `h4` 是一个本域 host。
- `onos-c2` 正常不知道 `h1` 是一个本域 host。
- `tools/build_netcfg.py --domain c1` 会生成 `domainBoundaryConfig`，在边界 `r2` 上安装到直接邻域 `c2` router SID 的静态路由。
- `tools/build_netcfg.py --domain c2` 会在边界 `r3` 上安装到直接邻域 `c1` router SID 的静态路由。

也就是说，跨域静态路由默认只安装在直接相连的边界 router 上，并且只用于 SRv6 SID 可达。跨域 host `/128` 不会自动发布到外域；如果需要跨域通信，应先在入口 router 安装 SRv6 transit policy。

## 8. 本域知道多少远端域信息

在多控制器标准流程里，一个域不会完整知道非本域拓扑。它只知道和自己直接相连的邻域边界 SID 应该交给哪个跨域下一跳。

### 8.1 onos-c1 知道什么

`onos-c1` 对本域信息比较完整：

```text
本域设备:
  r1, r2

本域主机:
  h1 = 2001:1:1::10
  h2 = 2001:2:1::10

本域链路:
  r1-r2

本域 router 属性:
  r1.myStationMac, r1.mySid
  r2.myStationMac, r2.mySid
```

对直接邻域 `c2`，`onos-c1` 通过 `domainBoundaryConfig` 只知道：

```text
边界设备:
  r2

边界端口:
  r2 连接 r3 的端口

跨域下一跳:
  r3.myStationMac

邻域 SID 前缀:
  fc00:0:3::/128     # r3 SID
  fc00:0:4::/128     # r4 SID
```

这些信息来自完整拓扑 JSON，不是由 `r2` 在运行时自己发现出来的。

### 8.2 边界 router r2 知道什么

`r2` 的数据面不会保存“c2 的完整拓扑图”。它只收到 ONOS 下发的表项，例如：

```text
routing_v6_table:
  fc00:0:3::/128   -> nextHopMac = r3.myStationMac
  fc00:0:4::/128   -> nextHopMac = r3.myStationMac

l2_exact_table:
  r3.myStationMac -> r2-r3 边界端口
```

所以 `r2` 知道的是：

```text
去这些远端 IP/SID，下一跳统一发给 r3.myStationMac。
```

`r2` 不知道：

```text
h3/h4 host 前缀
h4.mac
h4 接在 r4 的哪个端口
r3-r4 域内链路如何继续转发
onos-c2 的完整 host store
```

这些由 `c2` 域里的 `r3/r4` 自己处理。

### 8.3 非边界 router r1 知道什么

严格两域拆分下，`r1` 默认只知道本域：

```text
h1, h2
r1-r2
r2.myStationMac
```

它不会自动知道：

```text
2001:4:1::10/128 -> r2.myStationMac
fc00:0:3::/128   -> r2.myStationMac
```

插入 SRv6 policy 后，入口 `r1` 会先把目的切到本域可达的 `r2 SID`，随后每个边界 router 只需要把包送到直接邻域 SID。

因此可以把当前两域信息可见性理解为：

```text
r1: 通常只知道本域。
r2: 作为边界，知道直接邻域 SID -> r3.myStationMac -> 边界端口。
r3/r4: 由 c2 控制器负责继续处理 c2 域内转发。
```

## 9. SRv6 在这里的作用

多控制器域之间不依赖普通 IPv6 自动互通。SRv6 policy 的作用是显式指定跨域路径，把流量逐段引导到每个相邻域 SID。

如果执行：

```bash
./tools/insert_srv6.py \
  --topology-file topologies/generated/topology-linear-4r-2domain-test.json \
  --domain-map target/2domain-runtime/domains.json \
  --clear r1 r2 r3 h4
```

入口 `r1` 会新增一条 `srv6_transit` 规则：

```text
2001:4:1::10/128 -> 插入 SRH: r2 SID -> r3 SID -> h4 IP
```

这时 `h1` 发出的原始包还是普通 IPv6：

```text
IPv6 dst = 2001:4:1::10
Ethernet dst = r1.myStationMac
```

但 `r1` 收到后会把包改成带 SRH 的 SRv6 包：

```text
IPv6:
  dst = fc00:0:2::
  next_hdr = SRv6
SRH:
  segment_left = 2
  segments = [2001:4:1::10, fc00:0:3::, fc00:0:2::]
```

随后：

- `r2` 处理自己的 SID `fc00:0:2::`，把目的切到 `fc00:0:3::`。
- `r3` 处理自己的 SID `fc00:0:3::`，把目的切回最终目的 `2001:4:1::10`，并弹出 SRH。
- `r4` 按普通 IPv6 最后一跳交付给 `h4`。

因此，文档中提到 SRv6 是为了说明多域跨域通信依赖的显式路径机制。单 ONOS 全拓扑模式下，如果你没有插入 SRv6 但直接 ping 成功，就应按第 5 节的普通 IPv6 路由理解。

## 10. 逐跳信息对照

普通 IPv6，无 SRv6，适用于单 ONOS 全拓扑：

| 阶段 | 包里携带的关键信息 | 当前节点本地知道的信息 | 当前节点处理结果 |
| --- | --- | --- | --- |
| `h1 -> r1` | `dstIP=2001:4:1::10`, `dstMac=r1.myStationMac` | `h1` 只知道默认网关 MAC | 把远端流量交给默认网关 |
| `r1 -> r2` | `dstIP=2001:4:1::10`, `dstMac=r2.myStationMac` | `r1` 有到 `h4 /128` 的路由，下一跳是 `r2` | 改二层 MAC，发给 `r2` |
| `r2 -> r3` | `dstIP=2001:4:1::10`, `dstMac=r3.myStationMac` | `r2` 有到 `h4 /128` 的路由，下一跳是 `r3` | 改二层 MAC，发给 `r3` |
| `r3 -> r4` | `dstIP=2001:4:1::10`, `dstMac=r4.myStationMac` | `r3` 有到 `h4 /128` 的路由，下一跳是 `r4` | 改二层 MAC，发给 `r4` |
| `r4 -> h4` | `dstIP=2001:4:1::10`, `dstMac=h4.mac` | `r4` 知道本地 host `h4` 的 MAC 和端口 | 发到 host-facing 端口 |

SRv6 插入后：

| 阶段 | 包里携带的关键信息 | 当前节点本地知道的信息 | 当前节点处理结果 |
| --- | --- | --- | --- |
| `h1 -> r1` | `dstIP=2001:4:1::10`, 无 SRH | `h1` 只知道默认网关 MAC | 把远端流量交给默认网关 |
| `r1 -> r2` | `dstIP=fc00:0:2::`, 有 SRH | `r1` 有 SRv6 policy | 插入 SRH，发给 `r2 SID` |
| `r2 -> r3` | `dstIP=fc00:0:3::`, 有 SRH | `r2` 有自己的 SID 和到 `r3 SID` 的路由 | 切到下一个 SID |
| `r3 -> r4` | `dstIP=2001:4:1::10`, 无 SRH | `r3` 有自己的 SID 和到 `h4` 的路由 | 切回最终目的并弹出 SRH |
| `r4 -> h4` | `dstIP=2001:4:1::10`, `dstMac=h4.mac` | `r4` 知道本地 host `h4` 的 MAC 和端口 | 发到 host-facing 端口 |

## 11. 关键文件

- [tools/build_netcfg.py](/home/p4/onos-ngsdn-app/tools/build_netcfg.py): 生成设备、主机、接口、链路和跨域边界配置。
- [src/main/java/org/onosproject/ngsdn/tutorial/Ipv6RoutingComponent.java](/home/p4/onos-ngsdn-app/src/main/java/org/onosproject/ngsdn/tutorial/Ipv6RoutingComponent.java): 安装普通 IPv6 host/SID 路由和下一跳二层规则。
- [src/main/java/org/onosproject/ngsdn/tutorial/DomainBoundaryRoutingComponent.java](/home/p4/onos-ngsdn-app/src/main/java/org/onosproject/ngsdn/tutorial/DomainBoundaryRoutingComponent.java): 读取跨域边界静态路由并下发到边界 router。
- [src/main/java/org/onosproject/ngsdn/tutorial/NdpReplyComponent.java](/home/p4/onos-ngsdn-app/src/main/java/org/onosproject/ngsdn/tutorial/NdpReplyComponent.java): 回复默认网关 NDP。
- [src/main/java/org/onosproject/ngsdn/tutorial/Srv6Component.java](/home/p4/onos-ngsdn-app/src/main/java/org/onosproject/ngsdn/tutorial/Srv6Component.java): 安装 SRv6 MySID 和 transit insert 规则。
- [p4src/ngsdn_tutorial.p4](/home/p4/onos-ngsdn-app/p4src/ngsdn_tutorial.p4): 定义数据面的表和动作。

## 12. 一句话总结

单 ONOS 全拓扑下直接 ping 能通时，说明当前包走普通 IPv6：主机只把包交给默认网关，每台 router 根据本地 `routing_v6_table` 选择下一跳 MAC，再根据 `l2_exact_table` 选择出端口。多 ONOS 域跨域通信则依赖显式 SRv6 policy，用来指定必须经过的相邻域 SID。
