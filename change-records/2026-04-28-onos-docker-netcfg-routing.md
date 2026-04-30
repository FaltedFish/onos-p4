# 2026-04-28 ONOS Docker 网络、netcfg 与 IPv6 路由修复

## 背景问题

本次排查和修复覆盖了三个连续问题：

- ONOS 启动后宿主机无法访问 `http://127.0.0.1:8181`。
- ONOS 能看到设备和主机，但 `/onos/v1/links` 为空。
- Mininet 中跨路由器 IPv6 测试，例如 `h1 ping6 2001:2:1::10`，最初 100% 丢包。

## 改造点

### 1. ONOS Docker 网络

原因：

默认 Docker `docker0` bridge 异常，宿主机没有 `172.17.0.1/16` 地址和 `172.17.0.0/16` 路由。容器虽然被分配到 `172.17.0.2`，`docker ps` 也显示 `8181` 已发布，但宿主机访问 `127.0.0.1:8181` 时 docker-proxy 无法转发到容器。

解决：

在 `env/create_onos.sh` 中增加项目专用 Docker bridge：

```text
ONOS_DOCKER_NETWORK=onos-ngsdn
ONOS_DOCKER_SUBNET=172.20.0.0/16
ONOS_DOCKER_GATEWAY=172.20.0.1
```

ONOS 容器启动时显式使用：

```text
--network onos-ngsdn
```

同时将默认 `NETCFG_IP` 改为 `172.20.0.1`，保证 ONOS 容器可以访问宿主机上的 BMv2 gRPC 端口。

### 2. netcfg links 配置

原因：

`tools/build_netcfg.py` 生成的 link 配置中包含 `isDurable` 字段。ONOS 2.7 的 link basic config 不接受该字段，推送配置时 REST 返回 `207 Multi-Status`，导致 devices/hosts 生效，但 links 被拒绝。

解决：

移除 link 配置中的 `isDurable`，只保留：

```json
{
  "basic": {
    "type": "DIRECT"
  }
}
```

### 3. REST 部分失败检测

原因：

原脚本使用 `curl --fail`，但 HTTP `207` 仍属于 2xx，不会被当成失败，导致 netcfg 部分失败被静默忽略。

解决：

在 `env/create_onos.sh` 中增加带 HTTP 状态码检查的 REST 调用逻辑，将 `207` 视为失败并输出响应 body，避免后续再出现“脚本显示 ready，但配置实际部分失败”的情况。

### 4. IPv6 路由拓扑来源

原因：

ONOS 2.7 的 `netcfglinksprovider` 不会直接把 netcfg links 变成 `/onos/v1/links` 中的静态链路；它更像是 LLDP link 发现的配置来源。当前 BMv2/P4Runtime 环境里 `/onos/v1/links` 可能为空，原 `Ipv6RoutingComponent` 只依赖 `LinkService` 计算路径，因此只能下发本地直连主机路由，无法下发跨路由器远端主机路由。

解决：

修改 `Ipv6RoutingComponent`，路由计算同时读取 netcfg 中的 `LinkKey`：

- `LinkService` 中已有的 egress links。
- `NetworkConfigService.getSubjects(LinkKey.class)` 中配置的静态 links。

这样即使 `/onos/v1/links` 为空，也能基于 netcfg 拓扑计算下一跳，并给所有远端主机下发 IPv6 /128 路由。

## 涉及文件

- `env/create_onos.sh`
- `tools/build_netcfg.py`
- `src/main/java/org/onosproject/ngsdn/tutorial/Ipv6RoutingComponent.java`
- `README.md`

## 验证结果

验证环境：

```bash
ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring
```

验证结果：

- ONOS UI 可通过 `http://127.0.0.1:8181/onos/ui/` 访问。
- ONOS 能看到 4 个 BMv2 设备，且 `available=true`。
- 修正后的 netcfg 推送返回 `200 OK`，不再返回 `207`。
- `r1` 上已下发到 `2001:2:1::10` 的远端 IPv6 路由，下一跳为 `r2` 的 myStation MAC。
- Mininet 中 `h1 ping6 2001:2:1::10` 测试通过。

