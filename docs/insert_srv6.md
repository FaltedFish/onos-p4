# SRv6 规则插入脚本使用说明

本文说明如何使用 `./tools/insert_srv6.py` 按拓扑节点名向 ONOS 下发
SRv6 transit insert 规则。

## 脚本用途

`./tools/insert_srv6.py` 是现有 ONOS CLI 命令 `srv6-insert` 的封装。
用户不需要手动查 router SID 或 host IPv6 地址，可以直接输入拓扑中的节点名：

```bash
./tools/insert_srv6.py r1 r2 r4 h3
```

脚本会从拓扑文件解析：

```text
r1 -> device:bmv2:r1
r2 -> r2 的 mySid
r4 -> r4 的 mySid
h3 -> h3 的 IPv6 地址，去掉 /64 前缀长度
```

然后执行等价的 ONOS CLI 命令：

```text
srv6-insert device:bmv2:r1 fc00:0:2:: fc00:0:4:: 2001:3:1::10
```

## 前置条件

运行前确认：

- ONOS 容器已经由 `./env/create_onos.sh` 创建并安装本应用。
- ONOS 已加载与 Mininet 一致的拓扑 netcfg。
- `target/env/` 下存在当前环境的 `topology-*.json` 文件。
- 当前目录是仓库根目录 `/home/p4/onos-ngsdn-app`。

如果拓扑文件不在默认位置，可以用 `--topology-file` 显式指定。

## 快速使用

先查看解析结果，不真正下发：

```bash
./tools/insert_srv6.py --dry-run r1 r2 r4 h3
```

预期输出类似：

```text
topology: /home/p4/onos-ngsdn-app/target/env/topology-ring-4r-1h.json
srv6-insert device:bmv2:r1 fc00:0:2:: fc00:0:4:: 2001:3:1::10
```

确认无误后下发规则：

```bash
./tools/insert_srv6.py r1 r2 r4 h3
```

如果希望下发前清除 `r1` 上已有 SRv6 transit 规则：

```bash
./tools/insert_srv6.py --clear r1 r2 r4 h3
```

## 参数含义

命令格式：

```bash
./tools/insert_srv6.py [选项] ingress segment... destination
```

示例：

```bash
./tools/insert_srv6.py r1 r2 h3
./tools/insert_srv6.py r1 r2 r4 h3
./tools/insert_srv6.py r1 r2 2001:3:1::10
```

参数解释：

```text
ingress      下发规则的入口 router，必须是拓扑中的 router 名，例如 r1。
segment...   中间 SRv6 SID 节点，必须是 router 名，例如 r2、r4。
destination  最终目的，可以是 host 名，例如 h3；也可以直接写 IPv6 地址。
```

当前 P4 程序和 Java 控制面只支持 2 或 3 个 SRv6 entry。因此 `ingress`
之后必须有 2 或 3 个参数：

```text
r1 r2 h3        合法，对应 2 个 entry：r2 SID + h3 IP
r1 r2 r4 h3     合法，对应 3 个 entry：r2 SID + r4 SID + h3 IP
r1 r2 r3 r4 h5  不合法，entry 数量超过 3
```

## 拓扑文件选择

默认情况下，脚本读取 `target/env/` 下最近修改的 `topology-*.json`：

```bash
./tools/insert_srv6.py r1 r2 r4 h3
```

显式指定拓扑文件：

```bash
./tools/insert_srv6.py \
  --topology-file target/env/topology-ring-4r-1h.json \
  r1 r2 r4 h3
```

当同时存在多份拓扑文件，或者当前 ONOS/Mininet 使用的是自定义拓扑时，
建议显式传 `--topology-file`，避免读到旧文件。

## 常用选项

查看完整帮助：

```bash
./tools/insert_srv6.py --help
```

常用选项：

```text
--dry-run             只打印解析后的 ONOS CLI 命令，不实际下发。
--clear               插入前先执行 srv6-clear <device>。
--topology-file PATH  指定展开后的拓扑 JSON 文件。
--output-dir DIR      保存 ONOS CLI 输出日志，默认 target/srv6-insert。
```

脚本默认保留已有 SRv6 transit 规则。只有传入 `--clear` 时，才会先清理
入口设备上的旧规则。

## ONOS CLI 连接方式

默认连接方式与 `./tools/verify_srv6.py` 一致：

- 通过 `docker exec ${ONOS_CONTAINER:-onos}` 进入 ONOS 容器。
- 在容器内查找 Karaf client。
- 执行 `srv6-clear` 或 `srv6-insert`。

可用环境变量：

```text
ONOS_CONTAINER  ONOS 容器名，默认 onos。
DOCKER_CMD      Docker 命令，默认 docker。
ONOS_CLI_CMD    自定义 ONOS CLI 命令前缀；设置后不使用 docker exec。
```

示例：

```bash
ONOS_CONTAINER=onos ./tools/insert_srv6.py r1 r2 r4 h3
```

使用自定义 CLI 入口：

```bash
ONOS_CLI_CMD="/opt/onos/apache-karaf-4.2.9/bin/client -h 127.0.0.1 -a 8101 -u onos -p rocks -b" \
  ./tools/insert_srv6.py r1 r2 r4 h3
```

## 输出文件

实际下发时，ONOS CLI 输出会保存到：

```text
target/srv6-insert/
```

例如：

```text
target/srv6-insert/srv6-insert_device_bmv2_r1_fc00_0_2_fc00_0_4_2001_3_1_10.log
```

命令失败时，脚本会返回非零退出码，并在错误信息中提示对应日志文件。

## 验证规则是否下发

下发后可以进入 ONOS CLI 查看：

```text
flows
```

期望在入口设备上看到 `srv6_transit` 表项，例如：

```text
device:bmv2:r1
srv6_transit
srv6_t_insert_3
```

也可以用 SRv6 自动化验证脚本做端到端检查：

```bash
./tools/verify_srv6.py --skip-build
```

详细说明见 [verify_srv6.md](verify_srv6.md)。

## 常见错误

入口节点不是 router：

```text
ERROR: Ingress node 'h1' is not a router in this topology
```

中间 segment 节点不是 router：

```text
ERROR: Segment node 'h2' is not a router in this topology
```

SRv6 entry 数量不合法：

```text
ERROR: Expected ingress router plus 2 or 3 SRv6 entries
```

找不到默认拓扑文件：

```text
ERROR: No topology files found under /home/p4/onos-ngsdn-app/target/env
```

处理方式：

- 先运行 `./env/create_onos.sh` 生成当前环境拓扑。
- 或使用 `--topology-file` 指定正确的展开拓扑 JSON。
- 确认 ONOS 和 Mininet 使用的是同一份拓扑文件。
