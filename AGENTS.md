# Repository Guidelines

## Project Structure & Module Organization

This repository is an ONOS 2.7 application for a BMv2/P4Runtime multi-router IPv6 topology. Java control-plane code lives under `src/main/java/org/onosproject/ngsdn/tutorial`, with components for L2 bridging, NDP replies, IPv6 routing, SRv6, CLI commands, and pipeconf loading. ONOS pipeconf resources are in `src/main/resources`. P4 source is `p4src/ngsdn_tutorial.p4`; compiled P4 artifacts are written to `p4build/` and synced into resources. Mininet topology code is in `mininet/`, environment entrypoints are in `env/`, helper scripts are in `tools/`, and change notes are in `change-records/`.

## Build, Test, and Development Commands

- `./build.sh`: builds the ONOS OAR with Maven using `mvn clean package -DskipTests`.
- `BUILD_P4=1 ./build.sh`: compiles P4 first, then packages the ONOS app.
- `./tools/build_p4.sh`: compiles `p4src/ngsdn_tutorial.p4` with `p4c`, updates `p4build/`, `src/main/resources/bmv2.json`, and `src/main/resources/p4info.txt`.
- `mvn test`: runs Maven tests if test classes are present.
- `./env/create_onos.sh`: creates/configures the ONOS Docker environment, installs the OAR, and pushes generated netcfg.
- `./env/create_mininet.sh`: cleans Mininet state and starts the BMv2 Mininet topology.

For topology variants, keep ONOS and Mininet variables aligned, for example:
`ROUTERS=4 HOSTS_PER_ROUTER=2 TOPOLOGY=ring ./env/create_onos.sh`.

## Coding Style & Naming Conventions

Java follows ONOS conventions: 4-space indentation, package names under `org.onosproject.ngsdn.tutorial`, `UpperCamelCase` classes, `lowerCamelCase` methods and fields, and static constants in `UPPER_SNAKE_CASE`. Prefer ONOS service APIs and existing helpers in `common/` before adding new abstractions. Shell scripts should use `bash`, `set -euo pipefail`, and environment-variable overrides matching existing scripts.

## Testing Guidelines

JUnit is available through Maven, but this repository currently has no committed `src/test` tree. Add Java tests under `src/test/java` with names ending in `Test`. For P4 or topology changes, also verify runtime behavior with ONOS and Mininet: check `devices`, `links`, `hosts`, `flows`, `groups`, then run `pingall6` in the Mininet CLI.

## Commit & Pull Request Guidelines

Recent commits use short, direct summaries, including Chinese descriptions. Keep messages concise and action-oriented, for example `Fix netcfg generation for ring topology`. Pull requests should describe the topology tested, list commands run, mention generated artifacts intentionally updated, and link any relevant issue or change record.

## Security & Configuration Tips

Do not hard-code local credentials, IPs, or tool paths. Use existing variables such as `ONOS_URL`, `ONOS_AUTH`, `NETCFG_IP`, `P4C_CMD`, and `MVN_CMD`. Be careful with Docker, Mininet, and `sudo mn -c` because they modify host networking state.
