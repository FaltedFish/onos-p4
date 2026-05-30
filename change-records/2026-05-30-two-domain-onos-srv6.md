# 2026-05-30 两域独立 ONOS + SRv6 跨域互通

## 背景

需要把原来的单 ONOS 控制器实验扩展为两控制器域演示。例如：

```text
c1: r1, r2
c2: r3, r4
```

Mininet 仍运行同一个 BMv2/P4Runtime 数据面拓扑，但每个 ONOS 容器只控制
自己域内的 router。跨域 SRv6 路径仍由外部程序或 `tools/insert_srv6.py`
下发，不新增 REST API。

## 解决方式

拓扑和 netcfg：

- `tools/build_topology.py` 支持 `routers[].domain`，未配置时默认
  `default`，保持单控制器拓扑兼容。
- `tools/build_netcfg.py` 新增 `--domain DOMAIN`：
  - 只生成本域 `devices`。
  - 只生成本域 host 和 host-facing interface。
  - 只生成两端都在本域内的 ONOS link。
  - 对跨域链路生成 `domainBoundaryConfig`，包含本域边界设备、端口、对端
    router MAC、对端 SID、外域 host/SID `/128` 前缀。
- 新增 `topologies/linear-4r-2domain.json` 作为 r1-r2-r3-r4 两域示例。

ONOS 控制面：

- 新增 `DomainBoundaryConfig`，注册为 app subject netcfg。
- 新增 `DomainBoundaryRoutingComponent`：
  - 在本域边界 router 下发外域下一跳 MAC 的 `l2_exact_table` 规则。
  - 下发外域 host `/128` 和 SID `/128` 的 `routing_v6_table` 规则。
  - 不改变 MySID、SRv6 transit insert、NDP、L2 bridging 原有行为。
- 运行测试中发现 `routing_v6_table` 可能早于 SELECT group 写入 BMv2，导致
  P4Runtime 报 `Invalid member / group id`。已修复为等待 group 状态变为
  `ADDED` 后再下发依赖该 group 的 route entries。

脚本：

- `env/create_onos.sh` 支持：
  - `DOMAIN=c1`
  - 多实例容器名：`ONOS_CONTAINER=onos-c1`
  - 多实例 REST 端口：`ONOS_REST_PORT=8281`
  - 自动把宿主机 REST 端口偏移应用到 SSH/gRPC/OpenFlow 等 ONOS 映射端口。
  - domain 模式下生成 `netcfg-...-DOMAIN.json` 并只推送本域 netcfg。
- `tools/insert_srv6.py` 新增 `--domain-map FILE`，按 ingress router 的
  `domain` 自动选择目标 ONOS CLI 环境。

## 涉及文件

新增：

- `src/main/java/org/onosproject/ngsdn/tutorial/DomainBoundaryRoutingComponent.java`
- `src/main/java/org/onosproject/ngsdn/tutorial/common/DomainBoundaryConfig.java`
- `topologies/linear-4r-2domain.json`

修改：

- `src/main/java/org/onosproject/ngsdn/tutorial/MainComponent.java`
- `tools/build_topology.py`
- `tools/build_netcfg.py`
- `tools/insert_srv6.py`
- `env/create_onos.sh`
- `docs/create_environment.md`
- `docs/insert_srv6.md`

## 使用方式

生成两域拓扑：

```bash
./tools/build_topology.py \
  --config topologies/linear-4r-2domain.json \
  --output target/env/topology-linear-4r-2domain.json
```

如果本机已有默认 `onos` 容器正在尝试连接 `9559` 起始的 BMv2 gRPC 端口，
建议为两域测试拓扑换一组 BMv2 端口：

```bash
./tools/build_topology.py \
  --config topologies/linear-4r-2domain.json \
  --grpc-base 9659 \
  --thrift-base 9190 \
  --output target/env/topology-linear-4r-2domain-test.json
```

启动两个 ONOS 域：

```bash
DOMAIN=c1 \
ONOS_CONTAINER=onos-c1 \
ONOS_REST_PORT=8281 \
RECREATE_ONOS=1 \
TOPOLOGY_FILE=target/env/topology-linear-4r-2domain-test.json \
./env/create_onos.sh

DOMAIN=c2 \
ONOS_CONTAINER=onos-c2 \
ONOS_REST_PORT=8381 \
RECREATE_ONOS=1 \
TOPOLOGY_FILE=target/env/topology-linear-4r-2domain-test.json \
./env/create_onos.sh
```

启动同一份 Mininet 数据面：

```bash
TOPOLOGY_FILE=target/env/topology-linear-4r-2domain-test.json \
./env/create_mininet.sh
```

创建 domain map：

```bash
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

下发双向 SRv6 policy：

```bash
./tools/insert_srv6.py \
  --topology-file target/env/topology-linear-4r-2domain-test.json \
  --domain-map target/2domain-runtime/domains.json \
  --clear r1 r2 r3 h4

./tools/insert_srv6.py \
  --topology-file target/env/topology-linear-4r-2domain-test.json \
  --domain-map target/2domain-runtime/domains.json \
  --clear r4 r3 r2 h1
```

在 Mininet 中验证：

```text
h1 ping6 -c 3 2001:4:1::10
h4 ping6 -c 3 2001:1:1::10
```

## 验证结果

已执行：

```bash
mvn -DskipTests package
python3 -m py_compile tools/build_topology.py tools/build_netcfg.py tools/insert_srv6.py
bash -n env/create_onos.sh env/create_mininet.sh
```

两域运行时验证：

- `onos-c1` 只加载 `r1,r2`。
- `onos-c2` 只加载 `r3,r4`。
- `domainBoundaryConfig` 正确生成：
  - c1 边界：`device:bmv2:r2` port `2` -> `00:aa:00:00:00:03`
  - c2 边界：`device:bmv2:r3` port `1` -> `00:aa:00:00:00:02`
- `target/2domain-runtime/mininet-result.json` 中 `passed=true`：
  - `h1 -> h4`：3 发 3 收，0% packet loss。
  - `h4 -> h1`：3 发 3 收，0% packet loss。

## 注意事项

- 多 ONOS 域共享同一个 Mininet 数据面时，每个 BMv2 router 只能被一个 ONOS
  控制。避免其它 ONOS 容器还持有同一组 gRPC 端口的旧 netcfg。
- 如需保留已有默认 `onos` 容器，建议两域测试使用非默认 BMv2 gRPC/Thrift
  端口，例如 `--grpc-base 9659 --thrift-base 9190`。
- ONOS 刚启动时 REST 激活基础 app 可能短暂返回 503，脚本会重试。
- 不要把本机 sudo 密码或其它凭据写入仓库文档；运行 `create_mininet.sh`
  时按本机环境输入 sudo 密码即可。
