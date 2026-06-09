# 2026-06-09 多域 SRv6 相邻边界 SID 转发

## 背景

多控制器域需要支持超过两个域，并允许流量经中间域传输。设计约束是：
不同域之间不通过普通 IPv6 自动互通，跨域必须由入口 router 安装 SRv6
transit policy；每个域只掌握直接相连邻域的边界可达信息。

## 变更

- `tools/build_netcfg.py` 的 `domainBoundaryConfig.routes[].prefixes` 现在只生成
  直接邻域 router SID `/128`。
- 不再向边界 router 发布直接邻域 host `/128`，避免跨域普通 IPv6 绕过
  SRv6 policy。
- 新增 `topologies/linear-6r-3domain.json`，用于验证 `c1 -> c2 -> c3`
  这类跨中间域 SRv6 路径。
- 更新多域环境和 SRv6 插入文档，说明跨中间域时 segment list 必须显式经过
  中间域 router SID。

## 验证

```bash
python3 -m py_compile tools/build_netcfg.py tools/build_topology.py tools/insert_srv6.py

./tools/build_topology.py \
  --config topologies/linear-6r-3domain.json \
  --grpc-base 9759 \
  --thrift-base 9290 \
  --output target/env/topology-linear-6r-3domain-test.json

./tools/build_netcfg.py \
  --topology-file target/env/topology-linear-6r-3domain-test.json \
  --domain c1 \
  --output target/env/netcfg-linear-6r-3domain-test-c1.json

./tools/build_netcfg.py \
  --topology-file target/env/topology-linear-6r-3domain-test.json \
  --domain c2 \
  --output target/env/netcfg-linear-6r-3domain-test-c2.json

./tools/build_netcfg.py \
  --topology-file target/env/topology-linear-6r-3domain-test.json \
  --domain c3 \
  --output target/env/netcfg-linear-6r-3domain-test-c3.json
```

检查结果：

```text
c1: r2 -> c2 SID fc00:0:3::/128, fc00:0:4::/128
c2: r3 -> c1 SID fc00:0:1::/128, fc00:0:2::/128
c2: r4 -> c3 SID fc00:0:5::/128, fc00:0:6::/128
c3: r5 -> c2 SID fc00:0:3::/128, fc00:0:4::/128
```
