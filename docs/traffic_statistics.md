# BMv2 流量统计上报说明

本文面向不了解 ONOS、BMv2 和 P4Runtime 的读者，说明本项目中的端口流量统计是如何产生、如何上报到 ONOS、如何查看，以及本次为实现这条链路做了哪些改动。

## 先理解几个角色

### ONOS

ONOS 是控制器。它不直接转发数据包，而是负责：

- 发现设备、端口、链路和主机。
- 根据网络状态生成转发表项。
- 通过 P4Runtime 把 flow、group 等规则下发给 BMv2。
- 通过 REST API 或 Web UI 展示网络状态。

本项目的 ONOS app 是 `org.onosproject.ngsdn-multirouter`，打包产物是：

```text
target/ngsdn-multirouter-1.0-SNAPSHOT.oar
```

### BMv2

BMv2 是软件交换机，Mininet 里每个 `r1`、`r2`、`r3` 这样的路由器节点都是一个 BMv2 `simple_switch_grpc` 进程。

BMv2 真正处理数据包。它加载 P4 编译出来的 JSON pipeline：

```text
p4build/ngsdn_tutorial/ngsdn_tutorial.json
```

### P4 和 P4Info

P4 文件定义 BMv2 如何处理包：

```text
p4src/ngsdn_tutorial.p4
```

P4 编译后会生成两类关键产物：

- BMv2 JSON：给 BMv2 数据面加载。
- P4Info：告诉 ONOS 有哪些表、动作、counter 等 P4Runtime 对象。

本项目会把生成产物同步到 ONOS app resources：

```text
src/main/resources/bmv2.json
src/main/resources/p4info.txt
```

### P4Runtime

P4Runtime 是 ONOS 和 BMv2 之间的控制协议。ONOS 通过它做两类事情：

- 写入规则：例如下发 IPv6 路由、L2 转发、组播复制规则。
- 读取状态：本项目用它读取 P4 counter cell，得到端口收发包数和字节数。

## 当前流量统计上报链路

当前实现采用 P4Runtime counter 作为统计来源，不再从 flow counter 合成端口统计。

完整链路如下：

```text
Mininet host 发包
        |
        v
BMv2 按 P4 pipeline 转发
        |
        v
P4 per-port counter 累加
        |
        v
ONOS app 通过 P4Runtime read 读取 counter
        |
        v
Bmv2PortStatisticsProvider 转成 ONOS PortStatistics
        |
        v
ONOS REST /onos/v1/statistics/ports 可查看
```

## P4 数据面如何计数

严格来说，P4 数据面不主动“上报”统计。P4 做的是在 BMv2 转发包时维护 counter；
ONOS 通过 P4Runtime read API 主动读取这些 counter，然后再上报到 ONOS 自己的
statistics 服务。

P4 文件中新增了两个 indirect counter：

```p4
counter(PORT_COUNTER_SIZE, CounterType.packets_and_bytes) port_ingress_counter;
counter(PORT_COUNTER_SIZE, CounterType.packets_and_bytes) port_egress_counter;
```

counter size 是 `512`，覆盖 v1model `bit<9>` 端口空间。

### ingress counter

包进入 BMv2 ingress pipeline 后，按入端口计数：

```p4
port_ingress_counter.count((bit<32>)standard_metadata.ingress_port);
```

这个 counter 对应 ONOS 里的：

```text
packetsReceived
bytesReceived
```

### egress counter

包进入 BMv2 egress pipeline 后，按实际出端口计数：

```p4
port_egress_counter.count((bit<32>)standard_metadata.egress_port);
```

这个 counter 对应 ONOS 里的：

```text
packetsSent
bytesSent
```

因为 egress pipeline 会在组播复制后执行，所以这个 counter 能统计实际复制后的出端口流量。

CPU port 是 `255`，Java 侧不会把它作为普通端口上报到 ONOS port statistics。

## P4 代码实现路径

P4 侧实现都在：

```text
p4src/ngsdn_tutorial.p4
```

### 1. 定义 counter 大小

端口类型是 v1model 的 `bit<9>`，可表达 `0..511`，所以 counter size 定义为 `512`：

```p4
const bit<32> PORT_COUNTER_SIZE = 512;
```

这样 counter index 可以直接使用端口号。

### 2. 在 ingress control 中定义入端口 counter

`IngressPipeImpl` 内定义：

```p4
@name("port_ingress_counter")
counter(PORT_COUNTER_SIZE, CounterType.packets_and_bytes) port_ingress_counter;
```

`@name("port_ingress_counter")` 是给 P4Info/BMv2 使用的对象名。编译后 P4Info 中完整名称是：

```text
IngressPipeImpl.port_ingress_counter
```

Java 侧读取 counter 时必须使用这个完整名称。

### 3. 在 ingress apply 开始处计数

`IngressPipeImpl.apply` 一开始执行：

```p4
local_metadata.ingress_port = standard_metadata.ingress_port;
port_ingress_counter.count((bit<32>)standard_metadata.ingress_port);
```

含义：

- `standard_metadata.ingress_port` 是 BMv2 告诉 P4 的真实入端口。
- `count(index)` 会把当前包的 packet 数和 byte 数累加到对应 counter cell。
- 端口 `1` 的流量累加到 counter index `1`，端口 `2` 的流量累加到 index `2`。

这段代码放在 ingress pipeline 开始处，因此只要包进入 BMv2，就会被 ingress counter 统计，
不依赖后续命中哪张表。

### 4. 在 egress control 中定义出端口 counter

`EgressPipeImpl` 内定义：

```p4
@name("port_egress_counter")
counter(PORT_COUNTER_SIZE, CounterType.packets_and_bytes) port_egress_counter;
```

编译后 P4Info 中完整名称是：

```text
EgressPipeImpl.port_egress_counter
```

### 5. 在 egress apply 中按实际出端口计数

CPU port 的包会先补 packet-in header，然后计数并退出：

```p4
if (standard_metadata.egress_port == CPU_PORT) {
    hdr.cpu_in.setValid();
    hdr.cpu_in.ingress_port = local_metadata.ingress_port;
    port_egress_counter.count((bit<32>)standard_metadata.egress_port);
    exit;
}
```

普通转发包在 egress 末尾计数：

```p4
port_egress_counter.count((bit<32>)standard_metadata.egress_port);
```

组播复制后的每个副本都会进入 egress pipeline，因此这里统计的是复制后的实际出端口。

有一个特殊处理：如果组播副本准备从入端口发回去，会被丢弃并退出：

```p4
if (local_metadata.is_multicast == true &&
      standard_metadata.ingress_port == standard_metadata.egress_port) {
    mark_to_drop(standard_metadata);
    exit;
}
```

这类被丢弃的回环副本不会进入最后的 egress counter 计数。

### 6. 编译后产物中能看到 counter

运行：

```bash
./tools/build_p4.sh
```

会在 P4Info 中生成：

```text
IngressPipeImpl.port_ingress_counter
EgressPipeImpl.port_egress_counter
```

ONOS 读取 counter 依赖的是 P4Info 中的这些名称。

## ONOS app 如何读取并上报

Java 侧由这个组件负责：

```text
src/main/java/org/onosproject/ngsdn/tutorial/Bmv2PortStatisticsProvider.java
```

它是一个辅助 `DeviceProvider`，主要做四件事。

### 1. 找到要上报的端口

端口来源包括：

- netcfg 中的静态 link 端点。
- netcfg 中的 host/interface connect point。
- ONOS 已发现且 enabled 的 device ports。

然后排除 CPU port `255`。

### 2. 解析 BMv2 P4Runtime device ID

每台 BMv2 设备在 netcfg 中有 management address，例如：

```text
grpc://172.20.0.1:9559?device_id=0
```

Provider 从 `device_id=0` 解析出 P4Runtime device ID。这个 ID 必须和 Mininet 启动 BMv2 时传给 `simple_switch_grpc --device-id` 的值一致。

### 3. 读取 P4 counter

Provider 每 2 秒轮询一次本节点 master 的可用设备，通过 ONOS P4Runtime read API 读取：

```text
IngressPipeImpl.port_ingress_counter
EgressPipeImpl.port_egress_counter
```

核心逻辑是：

```java
client.read(p4DeviceId, pipeconf)
      .counterCells(Arrays.asList(PORT_INGRESS_COUNTER, PORT_EGRESS_COUNTER))
      .submitSync();
```

读取结果按 counter index 映射到 ONOS 端口号。index `1` 就是端口 `1`，index `2` 就是端口 `2`。

### 4. 上报 ONOS 标准端口统计

Provider 把 counter 转成 ONOS 标准 `DefaultPortStatistics`：

```text
ingress counter -> packetsReceived / bytesReceived
egress counter  -> packetsSent / bytesSent
```

然后调用：

```java
providerService.updatePortStatistics(deviceId, portStatistics);
```

上报后，可以通过 ONOS REST 看到。

## ONOS 统计代码实现路径

ONOS 侧实现都在：

```text
src/main/java/org/onosproject/ngsdn/tutorial/Bmv2PortStatisticsProvider.java
```

它不是主设备驱动，而是一个辅助 `DeviceProvider`。这个组件负责把 P4 counter 翻译成
ONOS 标准 `PortStatistics`。

### 1. 声明要读取的 P4 counter

Java 中定义了两个 `PiCounterId`：

```java
private static final PiCounterId PORT_INGRESS_COUNTER =
        PiCounterId.of("IngressPipeImpl.port_ingress_counter");
private static final PiCounterId PORT_EGRESS_COUNTER =
        PiCounterId.of("EgressPipeImpl.port_egress_counter");
```

这里必须使用 P4Info 里的完整名称。只写 alias `port_ingress_counter` 或
`port_egress_counter` 可能无法匹配 ONOS pipeconf 中的实体。

### 2. 注册为 ONOS DeviceProvider

组件启动时执行：

```java
providerService = deviceProviderRegistry.register(this);
```

注册后，它可以调用：

```java
providerService.updatePorts(...)
providerService.updatePortStatistics(...)
```

这两个调用分别用于告诉 ONOS：

- 设备有哪些端口。
- 每个端口当前的收发包数和字节数。

### 3. 定时轮询设备

启动后先等待 `INITIAL_SETUP_DELAY`，之后每 2 秒轮询一次：

```java
private static final int POLL_INTERVAL_SECONDS = 2;
```

轮询入口是：

```java
schedulePoll(...)
pollAllDevices()
pollDevice(deviceId)
```

只处理满足以下条件的设备：

```java
active
providerService != null
deviceService.isAvailable(deviceId)
mastershipService.isLocalMaster(deviceId)
```

也就是说，只有当前 ONOS 节点是该设备 master 时才会读取并上报统计。

### 4. 发布端口列表

每轮会先调用：

```java
updateConfiguredPorts(deviceId);
```

端口来自：

```java
networkConfigService.getSubjects(LinkKey.class)
networkConfigService.getSubjects(ConnectPoint.class)
deviceService.getPorts(deviceId)
```

然后排除 CPU port：

```java
ports.removeIf(portNumber -> portNumber.toLong() == CPU_PORT_ID);
```

最后通过：

```java
providerService.updatePorts(deviceId, portDescriptions);
```

把端口发布给 ONOS。没有这一步，ONOS 可能没有完整端口对象，后续 statistics 也没有稳定的端口承载对象。

### 5. 从 netcfg 解析 P4Runtime device ID

P4Runtime read 需要数字类型的 P4 device ID。这个值来自 netcfg：

```text
grpc://172.20.0.1:9559?device_id=0
```

代码读取 `BasicDeviceConfig.managementAddress()`，再解析 query string：

```java
final URI managementAddress = URI.create(config.managementAddress());
final String query = managementAddress.getRawQuery();
```

找到：

```text
device_id=0
```

然后转成 `long`：

```java
return Optional.of(Long.parseLong(keyValue[1]));
```

如果这个 ID 和 BMv2 启动时的 `--device-id` 不一致，P4Runtime read 会读不到正确设备。

### 6. 通过 P4Runtime read 读取 counter cell

核心读取逻辑在 `readPortCounters()`：

```java
response = client.read(p4DeviceId.get(), pipeconf.get())
        .counterCells(Arrays.asList(PORT_INGRESS_COUNTER, PORT_EGRESS_COUNTER))
        .submitSync();
```

这里用到三个对象：

- `P4RuntimeClient`：ONOS 到 BMv2 的 P4Runtime client。
- `p4DeviceId`：BMv2 P4Runtime device ID。
- `pipeconf`：ONOS 中绑定到该设备的 P4Info/Pipeline 描述。

如果读取失败，代码不会发布错误统计，而是跳过本轮：

```java
if (!response.isSuccess()) {
    return Optional.empty();
}
```

### 7. 将 counter cell 转成端口统计

P4Runtime 返回的是一组 `PiCounterCell`。每个 cell 有：

- counter ID：说明是 ingress counter 还是 egress counter。
- index：对应端口号。
- data：packet/byte 计数。

代码按 index 找到 ONOS 端口：

```java
final PortNumber portNumber = PortNumber.portNumber(counterCell.cellId().index());
```

然后根据 counter ID 写入不同字段：

```java
if (PORT_INGRESS_COUNTER.equals(counterCell.cellId().counterId())) {
    stats.packetsReceived = counterCell.data().packets();
    stats.bytesReceived = counterCell.data().bytes();
} else if (PORT_EGRESS_COUNTER.equals(counterCell.cellId().counterId())) {
    stats.packetsSent = counterCell.data().packets();
    stats.bytesSent = counterCell.data().bytes();
}
```

映射关系是：

```text
IngressPipeImpl.port_ingress_counter -> packetsReceived / bytesReceived
EgressPipeImpl.port_egress_counter   -> packetsSent / bytesSent
```

### 8. 构造 ONOS DefaultPortStatistics 并上报

每个端口最终构造成：

```java
DefaultPortStatistics.builder()
        .setDeviceId(deviceId)
        .setPort(portNumber)
        .setPacketsReceived(stats.packetsReceived)
        .setPacketsSent(stats.packetsSent)
        .setBytesReceived(stats.bytesReceived)
        .setBytesSent(stats.bytesSent)
        .build();
```

然后发布给 ONOS：

```java
providerService.updatePortStatistics(deviceId, portStatistics);
```

ONOS 收到后，REST 接口就能返回这些数据：

```text
/onos/v1/statistics/ports
```

## 旧实现和当前实现的区别

旧实现尝试从 ONOS flow counter 推导端口统计：

```text
l2_exact_table flow counter
        |
        v
解析 flow treatment 中的 set_egress_port
        |
        v
按输出端口合成 egress 统计
        |
        v
把对端 egress 镜像成本端 ingress
        |
        v
写 StatisticStore synthetic output flow
```

这个方案的问题是：

- 只覆盖 `l2_exact_table`，不覆盖所有真实经过端口的流量。
- 组播、NDP reply、ACL clone/drop、SRv6 等路径容易漏算。
- ingress 不是真实入端口统计，而是从对端 egress 推导。
- 需要维护 synthetic flow 和 counter delta 状态，逻辑复杂。

当前实现改为：

```text
P4 ingress/egress per-port counter
        |
        v
ONOS P4Runtime read
        |
        v
ONOS DefaultPortStatistics
        |
        v
/onos/v1/statistics/ports
```

优点是：

- 统计来源更接近数据面真实端口。
- ingress 和 egress 分别由 P4 pipeline 直接计数。
- 不需要从 flow treatment 推导端口。
- 不需要维护 synthetic flow/statistic store 状态。

## 如何启动并验证

推荐从干净环境启动：

```bash
cd /home/p4/onos-ngsdn-app

./env/create_onos.sh
./env/create_mininet.sh
```

`create_onos.sh` 默认会：

- 编译 P4。
- 重新构建 ONOS OAR。
- 安装并激活 app。
- 推送 netcfg。

默认变量是：

```text
BUILD_APP=1
BUILD_P4=1
```

因此直接运行 `./env/create_onos.sh` 会使用当前源码中的新计数方式。

如果 BMv2 已经在运行旧 pipeline，需要重启 Mininet：

```bash
./env/create_mininet.sh
```

否则 ONOS app 可能是新的，但 BMv2 数据面仍然没有新的 counter。

## 如何产生流量

进入 Mininet CLI 后执行：

```text
h1 ping6 2001:2:1::10
```

或者执行全量 IPv6 连通性测试：

```text
pingall6
```

## 如何查看端口统计

### 查看所有设备端口统计

在宿主机执行：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8181/onos/v1/statistics/ports
```

典型返回结构如下：

```json
{
  "statistics": [
    {
      "device": "device:bmv2:r1",
      "ports": [
        {
          "port": 1,
          "packetsReceived": 222,
          "packetsSent": 184,
          "bytesReceived": 26382,
          "bytesSent": 22135
        }
      ]
    }
  ]
}
```

字段含义：

```text
packetsReceived  从该端口进入 BMv2 的包数
bytesReceived    从该端口进入 BMv2 的字节数
packetsSent      从该端口发出 BMv2 的包数
bytesSent        从该端口发出 BMv2 的字节数
```

### 连续观察是否增长

可以连续请求两次，对比数字是否增长：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8181/onos/v1/statistics/ports

sleep 3

curl -sS --user onos:rocks \
  http://127.0.0.1:8181/onos/v1/statistics/ports
```

如果 Mininet 中正在 ping，相关端口的 packet/byte counter 应该增长。

如果安装了 `jq`，可以只看某个设备：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8181/onos/v1/statistics/ports |
  jq '.statistics[] | select(.device=="device:bmv2:r1")'
```

## Web UI 中按 a 为什么可能看不到

ONOS Web Topology 中按 `a` 显示的是 link load overlay。它通常依赖 ONOS 的 flow/link load 统计路径。

本次实现上报的是 ONOS 标准 port statistics：

```text
/onos/v1/statistics/ports
```

所以会出现这种情况：

- REST port statistics 有数字，并且会增长。
- Web Topology 按 `a` 不显示链路流量，或显示为 0。

这不代表 P4Runtime counter 没有工作。可以用下面的接口确认 link-load overlay 的状态：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8181/onos/v1/statistics/flows/link
```

如果返回里是：

```text
valid: false
```

说明 Web link load 没有可用的 flow/link 统计。当前标准查看入口应使用：

```text
/onos/v1/statistics/ports
```

## 本次做了哪些工作

### P4 侧

修改文件：

```text
p4src/ngsdn_tutorial.p4
```

新增：

- `PORT_COUNTER_SIZE = 512`
- `port_ingress_counter`
- `port_egress_counter`

在 ingress pipeline 起始处按入端口计数，在 egress pipeline 按实际出端口计数。

然后重新编译 P4：

```bash
./tools/build_p4.sh
```

生成并同步：

```text
p4build/ngsdn_tutorial/ngsdn_tutorial.json
p4build/ngsdn_tutorial.p4info.pb.txt
src/main/resources/bmv2.json
src/main/resources/p4info.txt
```

### Java/ONOS 侧

修改文件：

```text
src/main/java/org/onosproject/ngsdn/tutorial/Bmv2PortStatisticsProvider.java
```

删除旧逻辑：

- 不再从 `l2_exact_table` flow counter 推导端口。
- 不再遍历 `FlowRuleService.getFlowEntries()`。
- 不再从 flow treatment 解析输出端口。
- 不再写 `StatisticStore` synthetic output flow。
- 不再把对端 egress 镜像成本端 ingress。

新增逻辑：

- 引入 `P4RuntimeController`。
- 引入 `PiPipeconfService`。
- 从 netcfg `BasicDeviceConfig.managementAddress()` 解析 `device_id`。
- 读取 P4Runtime counter cell。
- 构造 `DefaultPortStatistics`。
- 调用 `DeviceProviderService.updatePortStatistics()` 上报。

### 构建验证

已验证命令：

```bash
./tools/build_p4.sh
mvn test
BUILD_P4=1 ./build.sh
```

`./tools/build_p4.sh` 可能输出一个已有警告：

```text
Target does not support default_action for IngressPipeImpl.routing_v6_table
```

这是 `routing_v6_table` 使用 action profile 时的既有警告，不影响本次 port counter 编译。

## 常见排查

### REST statistics 没有设备

检查设备是否在线：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8181/onos/v1/devices
```

设备应为：

```text
available: true
role: MASTER
protocol: P4Runtime
```

### counter 一直是 0

检查：

- Mininet 是否真的有流量，例如 `h1 ping6 2001:2:1::10` 是否成功。
- `./env/create_mininet.sh` 是否在 `./env/create_onos.sh` 之后重启过。
- ONOS 和 Mininet 是否使用同一组拓扑变量，例如 `ROUTERS`、`HOSTS_PER_ROUTER`、`TOPOLOGY`。
- netcfg 中的 `device_id` 是否和 BMv2 启动参数一致。

### ONOS 日志里读取 counter 失败

查看日志：

```bash
docker logs --tail 300 onos 2>&1 | grep Bmv2PortStatisticsProvider
```

常见原因：

- BMv2 没有加载最新 P4 JSON。
- ONOS app 没有安装最新 OAR。
- P4Info 中 counter 名称和 Java 中 `PiCounterId` 不一致。
- P4Runtime device ID 不匹配。

### Web UI 不显示链路负载

优先用 REST port statistics 判断新计数链路是否正常：

```bash
curl -sS --user onos:rocks \
  http://127.0.0.1:8181/onos/v1/statistics/ports
```

Web Topology 按 `a` 不显示，不等同于端口统计没有上报。当前实现目标是标准端口统计上报，不是 ONOS Web link-load overlay 适配。
