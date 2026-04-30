#!/usr/bin/env python3
"""Generate ONOS netcfg for mininet/multi_router_p4runtime.py."""

import argparse
import json
from pathlib import Path


DEFAULT_PIPECONF = "org.onosproject.ngsdn-multirouter"


def router_mac(router_id):
    suffix = f"{router_id:04x}"
    return f"00:aa:00:00:{suffix[:2]}:{suffix[2:]}"


def host_mac(router_index, host_index):
    return f"00:04:00:00:{router_index:02x}:{host_index:02x}"


def allocate_ports(num_routers, hosts_per_router, topology):
    next_port = {router: 1 for router in range(1, num_routers + 1)}
    host_ports = {}
    router_links = []

    def add_router_link(left, right):
        left_port = next_port[left]
        right_port = next_port[right]
        router_links.append((left, left_port, right, right_port))
        next_port[left] += 1
        next_port[right] += 1

    if topology == "linear":
        for router in range(1, num_routers):
            add_router_link(router, router + 1)
    elif topology == "ring":
        for router in range(1, num_routers + 1):
            add_router_link(router, router % num_routers + 1)
    elif topology == "mesh":
        for router in range(1, num_routers + 1):
            for distance in (1, 2):
                if router + distance <= num_routers:
                    add_router_link(router, router + distance)
    else:
        raise ValueError(f"Unsupported topology: {topology}")

    global_host_id = 1
    for router in range(1, num_routers + 1):
        for host in range(1, hosts_per_router + 1):
            host_ports[(router, host)] = (next_port[router], global_host_id)
            next_port[router] += 1
            global_host_id += 1

    return host_ports, router_links


def build_netcfg(args):
    host_ports, router_links = allocate_ports(
        args.routers, args.hosts_per_router, args.topology)
    netcfg = {
        "devices": {},
        "ports": {},
        "links": {},
        "hosts": {},
        "apps": {
            "org.onosproject.core": {
                "core": {
                    "linkDiscoveryMode": "STRICT"
                }
            }
        }
    }

    for router in range(1, args.routers + 1):
        device_id = args.device_id_base + router - 1
        device_uri = f"device:bmv2:r{router}"
        netcfg["devices"][device_uri] = {
            "basic": {
                "name": f"r{router}",
                "driver": args.driver,
                "pipeconf": args.pipeconf,
                "managementAddress": (
                    f"grpc://{args.ip}:{args.grpc_base + router - 1}"
                    f"?device_id={device_id}"
                )
            },
            "fabricDeviceConfig": {
                "myStationMac": router_mac(router),
                "mySid": f"fc00:0:{router}::",
                "isSpine": False
            }
        }

    for (router, host), (port, global_host_id) in sorted(host_ports.items()):
        device_uri = f"device:bmv2:r{router}"
        gateway = f"2001:{router}:{host}::254/64"
        netcfg["ports"][f"{device_uri}/{port}"] = {
            "interfaces": [
                {
                    "name": f"r{router}-h{global_host_id}",
                    "ips": [gateway]
                }
            ]
        }

        mac = host_mac(router - 1, host - 1)
        netcfg["hosts"][f"{mac}/None"] = {
            "basic": {
                "name": f"h{global_host_id}",
                "ips": [f"2001:{router}:{host}::10"],
                "locations": [f"{device_uri}/{port}"]
            }
        }

    for left, left_port, right, right_port in router_links:
        left_cp = f"device:bmv2:r{left}/{left_port}"
        right_cp = f"device:bmv2:r{right}/{right_port}"
        netcfg["links"][f"{left_cp}-{right_cp}"] = {
            "basic": {
                "type": "DIRECT"
            }
        }
        netcfg["links"][f"{right_cp}-{left_cp}"] = {
            "basic": {
                "type": "DIRECT"
            }
        }

    return netcfg


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ip", default="127.0.0.1",
                        help="Address ONOS uses to reach simple_switch_grpc")
    parser.add_argument("--routers", type=int, default=2)
    parser.add_argument("--hosts-per-router", type=int, default=1)
    parser.add_argument("--topology", choices=("linear", "ring", "mesh"),
                        default="linear")
    parser.add_argument("--grpc-base", type=int, default=9559)
    parser.add_argument("--device-id-base", type=int, default=0)
    parser.add_argument("--driver", default="bmv2")
    parser.add_argument("--pipeconf", default=DEFAULT_PIPECONF)
    parser.add_argument("--output", default="netcfg.json")
    args = parser.parse_args()

    if args.routers < 1:
        raise SystemExit("--routers must be >= 1")
    if args.hosts_per_router < 1:
        raise SystemExit("--hosts-per-router must be >= 1")

    output = Path(args.output)
    output.write_text(json.dumps(build_netcfg(args), indent=2) + "\n")
    print(f"Wrote {output}")


if __name__ == "__main__":
    main()
