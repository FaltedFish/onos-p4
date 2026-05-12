# 2026-05-12 ONOS Web 拓扑静态链路显示修复

## 背景问题

使用 4 router、每 router 2 host 的 ring 拓扑启动环境：

```bash
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_onos.sh
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_mininet.sh
```

ONOS Web 端显示：

```text
Devices        4
Links          0
Hosts          8
Topology SCCs  4
```

但检查 `/onos/v1/network/configuration` 可以看到 netcfg 中已经包含 8 条有向
静态 links，说明拓扑配置已推送到 ONOS，只是没有进入 ONOS `LinkService`。

## 原因

ONOS Web Topology 和 `/onos/v1/topology` 依赖 `LinkService` 中的链路。

ONOS 2.7 的 `org.onosproject.netcfglinksprovider` 会读取 netcfg links，但它
主要用这些配置约束 LLDP 链路发现；在当前 BMv2/P4Runtime 环境中，LLDP 链路
发现没有形成，所以 `/onos/v1/links` 仍为空。

此前 IPv6 路由组件已经能直接读取 netcfg `LinkKey` 计算路径，因此数据面路由
可以工作，但 Web Topology 仍无法画出设备之间的连线。

## 解决方式

新增 `StaticNetcfgLinkProvider`：

- 作为项目自身的 ONOS `LinkProvider` 注册到 `LinkProviderRegistry`。
- 启动后读取 `NetworkConfigService.getSubjects(LinkKey.class)`。
- 当链路两端设备都 available 时，将 netcfg 中的 `LinkKey` 发布为
  `Link.Type.DIRECT` 链路。
- 监听 netcfg 和设备可用性变化，动态同步新增、更新和删除的静态链路。
- 组件停用时撤销自身发布的链路。

这样 netcfg 中描述的拓扑会进入 ONOS `LinkService`，Web Topology 可以直接
显示设备间连线。

## 涉及文件

- `src/main/java/org/onosproject/ngsdn/tutorial/StaticNetcfgLinkProvider.java`

## 验证结果

构建验证：

```bash
./build.sh
```

结果：

```text
BUILD SUCCESS
target/ngsdn-multirouter-1.0-SNAPSHOT.oar
```

将新 OAR 安装到当前 ONOS 后验证 REST 状态：

```bash
curl -sS --user onos:rocks http://127.0.0.1:8181/onos/v1/links
curl -sS --user onos:rocks http://127.0.0.1:8181/onos/v1/topology
```

结果：

- `/onos/v1/links` 返回 8 条 `ACTIVE`、`DIRECT` 链路。
- `/onos/v1/topology` 返回：

```json
{"devices":4,"links":8,"clusters":1}
```

刷新 ONOS Web Topology 后，4 台 BMv2 router 可以显示为同一个 ring 拓扑。
