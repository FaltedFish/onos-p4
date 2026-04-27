# NG-SDN Multi-Router ONOS App

This app is based on the NG-SDN tutorial control-plane structure, adapted for
`mininet/multi_router_p4runtime.py` with BMv2 `simple_switch_grpc`.

It registers pipeconf `org.onosproject.ngsdn-multirouter` using:

- `src/main/resources/bmv2.json`
- `src/main/resources/p4info.txt`

Those files are copied from the current `ngsdn_tutorial` P4 build artifacts.

## Rebuild P4 Artifacts

If you change `/home/p4/behavioral-model/p4prog/ngsdn_tutorial.p4`, rebuild
with the same command used by the Mininet setup, then sync the outputs into this
app:

```bash
cd /home/p4/behavioral-model

p4c --target bmv2 --arch v1model --std p4-16 \
  -I /usr/share/p4c/p4include \
  -o p4prog/ngsdn_tutorial \
  --p4runtime-files p4prog/ngsdn_tutorial.p4info.pb.txt \
  p4prog/ngsdn_tutorial.p4

cp -f p4prog/ngsdn_tutorial/ngsdn_tutorial.json \
  onos-ngsdn-app/src/main/resources/bmv2.json

cp -f p4prog/ngsdn_tutorial.p4info.pb.txt \
  onos-ngsdn-app/src/main/resources/p4info.txt
```

## Build

```bash
cd /home/p4/behavioral-model/onos-ngsdn-app
./build.sh
```

If your user can access Docker only via sudo:

```bash
DOCKER_CMD="sudo docker" ./build.sh
```

The output is:

```text
target/ngsdn-multirouter-1.0-SNAPSHOT.oar
```

## Activate ONOS Base Apps

Run these against your ONOS 2.7 instance:

```bash
curl --fail -sSL --user onos:rocks -X POST \
  http://localhost:8181/onos/v1/applications/org.onosproject.drivers.bmv2/active

curl --fail -sSL --user onos:rocks -X POST \
  http://localhost:8181/onos/v1/applications/org.onosproject.protocols.grpc/active

curl --fail -sSL --user onos:rocks -X POST \
  http://localhost:8181/onos/v1/applications/org.onosproject.protocols.p4runtime/active

curl --fail -sSL --user onos:rocks -X POST \
  http://localhost:8181/onos/v1/applications/org.onosproject.lldpprovider/active

curl --fail -sSL --user onos:rocks -X POST \
  http://localhost:8181/onos/v1/applications/org.onosproject.netcfglinksprovider/active

curl --fail -sSL --user onos:rocks -X POST \
  http://localhost:8181/onos/v1/applications/org.onosproject.netcfghostprovider/active
```

`netcfghostprovider` loads the static host IP/location data generated in the
netcfg. You can also leave `hostprovider` active if your ONOS container already
started it.

## Install App

```bash
curl --fail -sSL --user onos:rocks \
  -X POST -H 'Content-Type:application/octet-stream' \
  'http://localhost:8181/onos/v1/applications?activate=true' \
  --data-binary @target/ngsdn-multirouter-1.0-SNAPSHOT.oar
```

## Generate And Push netcfg

If ONOS runs in Docker bridge mode, do not use `127.0.0.1` as `--ip`; use an
address reachable from the ONOS container, for example `172.17.0.1` or the host
NIC IP.

```bash
./tools/build_netcfg.py \
  --ip 127.0.0.1 \
  --routers 2 \
  --topology linear \
  --output netcfg-linear-2.json

curl --fail -sSL --user onos:rocks \
  -X POST -H 'Content-Type:application/json' \
  http://localhost:8181/onos/v1/network/configuration \
  -d @netcfg-linear-2.json
```

## Start Mininet

Use `p4info.pb.txt`, not `.p4i`, and force CPU port 255:

```bash
cd /home/p4/behavioral-model

sudo python3 mininet/multi_router_p4runtime.py \
  --grpc-exe /usr/bin/simple_switch_grpc \
  --json /home/p4/behavioral-model/p4prog/ngsdn_tutorial/ngsdn_tutorial.json \
  --p4info /home/p4/behavioral-model/p4prog/ngsdn_tutorial.p4info.pb.txt \
  --num-routers 2 \
  --topology linear \
  --cpu-port 255
```

## Verify

In ONOS:

```text
devices
ports
links
hosts
flows
groups
```

In Mininet:

```text
h1 ping6 -c 3 2001:2:1::10
pingall6
```
