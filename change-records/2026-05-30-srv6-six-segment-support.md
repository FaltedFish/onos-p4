# 2026-05-30 SRv6 扩展到 6 个 Segment Entry

## 背景

当前 SRv6 transit insert 功能只支持 2 或 3 个 SRv6 entry。较长路径中，
入口 router 之后只能经过很少的中间 SID，无法覆盖更多中间节点的实验拓扑。

本次目标是把 SRv6 entry 上限扩展到 6 个。这里的 entry 包含最后的目的 IPv6
地址，因此最多支持 5 个中间 SRv6 SID 节点加最终目的地址。

## 解决方式

P4 管线侧：

- 将 `SRV6_MAX_HOPS` 从 4 改为 6。
- 新增 `srv6_t_insert_4`、`srv6_t_insert_5`、`srv6_t_insert_6`。
- `srv6_transit` 表允许 `srv6_t_insert_2` 到 `srv6_t_insert_6`。
- 新增 action 按 SRH wire format 反向写入 segment list：
  `srv6_list[0]` 写最后一段，`srv6_list[num_segments - 1]` 写第一段。
- 6-entry SRH 插入时设置：
  - `hdr_ext_len = 12`
  - `segment_left = 5`
  - `last_entry = 5`
  - IPv6 payload length 增加 `104` 字节。
- `srv6_pop()` 现在会 invalidate `srv6_list[0]` 到 `srv6_list[5]`。

ONOS 控制面侧：

- `Srv6Component.insertSrv6InsertRule()` 的合法 segment 数量从 `2..3` 改为
  `2..6`。
- action 名称继续使用现有规则：
  `IngressPipeImpl.srv6_t_insert_<segmentCount>`。
- action 参数继续使用 `s1`、`s2`、...、`s6`，每个参数是 128-bit IPv6 地址。

工具和文档侧：

- `tools/insert_srv6.py` 支持入口 router 后跟 2 到 6 个 SRv6 entry。
- `tools/verify_srv6.py` 的 transit flow 检查支持传入 action 名称，默认仍保留
  原 3-entry smoke test。
- `docs/insert_srv6.md` 和 README 中的说明已同步到 2 到 6 个 entry。
- 已重新生成 P4 产物并同步到 ONOS 资源目录。

## 涉及文件

源码和脚本：

- `p4src/ngsdn_tutorial.p4`
- `src/main/java/org/onosproject/ngsdn/tutorial/Srv6Component.java`
- `tools/insert_srv6.py`
- `tools/verify_srv6.py`

文档：

- `README.md`
- `docs/insert_srv6.md`

生成产物：

- `p4build/ngsdn_tutorial.p4info.pb.txt`
- `p4build/ngsdn_tutorial/ngsdn_tutorial.json`
- `p4build/ngsdn_tutorial/ngsdn_tutorial.p4i`
- `src/main/resources/bmv2.json`
- `src/main/resources/p4info.txt`
- `target/classes/bmv2.json`
- `target/classes/p4info.txt`
- `target/ngsdn-multirouter-1.0-SNAPSHOT.jar`
- `target/ngsdn-multirouter-1.0-SNAPSHOT.oar`

运行验证产物：

- `target/srv6-verify-6hop-regression/`
- `target/srv6-verify-6entry/`
- `target/env/topology-ring-7r-1h.json`
- `target/env/netcfg-ring-7r-1h.json`

## 验证结果

已执行：

```bash
./tools/build_p4.sh
mvn package -DskipTests
python3 -m py_compile tools/insert_srv6.py tools/verify_srv6.py
./tools/insert_srv6.py --dry-run \
  --topology-file topologies/topology-ring-10r-1h.json \
  r1 r2 r3 r4 r5 r6 h7
```

结果：

- P4 编译通过，并生成 `srv6_t_insert_4`、`srv6_t_insert_5`、
  `srv6_t_insert_6`。
- Maven 打包通过。
- 6-entry dry run 生成：

```text
srv6-insert device:bmv2:r1 fc00:0:2:: fc00:0:3:: fc00:0:4:: fc00:0:5:: fc00:0:6:: 2001:7:1::10
```

运行环境验证：

- 原 4-router / 3-entry SRv6 smoke test 通过：
  `target/srv6-verify-6hop-regression/result.json` 中 `passed=true`。
- 7-router / 6-entry 上限验证通过：
  `target/srv6-verify-6entry/retry-after-flow-settle/summary.json` 中
  `passed=true`。
- 6-entry 策略为：

```text
r1 -> r2 SID -> r3 SID -> r4 SID -> r5 SID -> r6 SID -> h7 IP
```

对应 ONOS CLI 命令：

```text
srv6-insert device:bmv2:r1 fc00:0:2:: fc00:0:3:: fc00:0:4:: fc00:0:5:: fc00:0:6:: 2001:7:1::10
```

验证抓包确认：

- `r1-r2`：SRH `segmentsLeft=5`，`lastEntry=5`，header length `104`。
- `r4-r5`：SRH `segmentsLeft=2`。
- `r5-r6`：SRH `segmentsLeft=1`。
- `r6-r7`：SRH 已 pop，流量变回普通 ICMPv6，目的地址为
  `2001:7:1::10`。
- `h1 ping6 2001:7:1::10` 成功，3 发 3 收，0% packet loss。

## 注意事项

- 7-router 环境启动时 ONOS base app 初始化偶尔会短暂返回 503，例如
  `ApplicationAdminService not found`。等待服务恢复后重新执行配置可继续验证。
- 首次 6-entry batch 在部分 flow 仍处于 `PENDING_ADD` 时触发，导致第一次
  `h1 -> h7` ping 失败。等待 flow 收敛后重跑同一路径验证通过。
- 当前实现仍是静态 action 展开方式；如果未来要支持超过 6 个 entry，需要继续
  增加 P4 header stack 大小、insert action、Java 数量限制和工具文档。
