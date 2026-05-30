#!/usr/bin/env python3
"""Generate ONOS netcfg from an expanded topology JSON file."""

import argparse
import json
from pathlib import Path

from build_topology import build_topology_from_template, load_topology_file


DEFAULT_PIPECONF = "org.onosproject.ngsdn-multirouter"
DEFAULT_DOMAIN = "default"
APP_NAME = "org.onosproject.ngsdn-multirouter"


def strip_prefix(ip_prefix):
    return ip_prefix.split("/", 1)[0]


def device_uri(router_name):
    return f"device:bmv2:{router_name}"


def router_domain(router):
    return router.get("domain", DEFAULT_DOMAIN)


def topology_indexes(topology):
    routers = {router["name"]: router for router in topology["routers"]}
    hosts_by_router = {}
    for host in topology["hosts"]:
        hosts_by_router.setdefault(host["router"], []).append(host)
    return routers, hosts_by_router


def validate_domain(topology, domain):
    if domain is None:
        return
    domains = {router_domain(router) for router in topology["routers"]}
    if domain not in domains:
        raise ValueError(
            f"Domain {domain!r} is not present in topology; available: "
            f"{', '.join(sorted(domains))}")


def in_domain(router, domain):
    return domain is None or router_domain(router) == domain


def host_in_domain(host, routers, domain):
    return in_domain(routers[host["router"]], domain)


def host_prefix(host):
    return f"{strip_prefix(host['ip'])}/128"


def sid_prefix(router):
    return f"{strip_prefix(router['mySid'])}/128"


def router_ports(topology, router_name):
    ports = set()
    for link in topology["links"]:
        for endpoint in ("left", "right"):
            if link[endpoint]["router"] == router_name:
                ports.add(link[endpoint]["port"])
    for host in topology["hosts"]:
        if host["router"] == router_name:
            ports.add(host["routerPort"])
    return sorted(ports)


def port_descriptions(topology, router_name):
    return {
        str(port): {
            "number": port,
            "name": f"{router_name}-p{port}",
            "enabled": True,
            "type": "packet",
            "speed": 10000,
        }
        for port in router_ports(topology, router_name)
    }


def build_netcfg(topology, args):
    domain = getattr(args, "domain", None)
    validate_domain(topology, domain)
    routers, _ = topology_indexes(topology)
    local_router_names = {
        router["name"] for router in topology["routers"]
        if in_domain(router, domain)
    }

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

    for router in topology["routers"]:
        if router["name"] not in local_router_names:
            continue
        uri = device_uri(router["name"])
        netcfg["devices"][uri] = {
            "basic": {
                "name": router["name"],
                "driver": args.driver,
                "pipeconf": args.pipeconf,
                "managementAddress": (
                    f"grpc://{args.ip}:{router['grpcPort']}"
                    f"?device_id={router['deviceId']}"
                )
            },
            "fabricDeviceConfig": {
                "myStationMac": router["myStationMac"],
                "mySid": router["mySid"],
                "isSpine": False
            },
            "ports": port_descriptions(topology, router["name"])
        }

    for host in topology["hosts"]:
        if not host_in_domain(host, routers, domain):
            continue
        uri = device_uri(host["router"])
        port = host["routerPort"]
        netcfg["ports"][f"{uri}/{port}"] = {
            "interfaces": [
                {
                    "name": f"{host['router']}-{host['name']}",
                    "ips": [host["gateway"]]
                }
            ]
        }
        netcfg["hosts"][f"{host['mac']}/None"] = {
            "basic": {
                "name": host["name"],
                "ips": [strip_prefix(host["ip"])],
                "locations": [f"{uri}/{port}"]
            }
        }

    for link in topology["links"]:
        left = link["left"]
        right = link["right"]
        if left["router"] not in local_router_names or right["router"] not in local_router_names:
            continue
        left_cp = f"{device_uri(left['router'])}/{left['port']}"
        right_cp = f"{device_uri(right['router'])}/{right['port']}"
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

    if domain is not None:
        boundary_routes = build_boundary_routes(topology, domain)
        if boundary_routes:
            netcfg["apps"][APP_NAME] = {
                "domainBoundaryConfig": {
                    "domain": domain,
                    "routes": boundary_routes,
                }
            }

    return netcfg


def build_boundary_routes(topology, domain):
    routers, hosts_by_router = topology_indexes(topology)
    routes = []

    for link in topology["links"]:
        left = link["left"]
        right = link["right"]
        left_router = routers[left["router"]]
        right_router = routers[right["router"]]
        left_domain = router_domain(left_router)
        right_domain = router_domain(right_router)
        if left_domain == right_domain:
            continue

        if left_domain == domain:
            local_endpoint = left
            remote_router = right_router
        elif right_domain == domain:
            local_endpoint = right
            remote_router = left_router
        else:
            continue

        route = {
            "device": device_uri(local_endpoint["router"]),
            "port": local_endpoint["port"],
            "nextHopMac": remote_router["myStationMac"],
            "nextHopSid": remote_router["mySid"],
            "remoteDomain": router_domain(remote_router),
            "remoteRouter": remote_router["name"],
            "prefixes": sorted(remote_prefixes(topology, router_domain(remote_router))),
        }
        routes.append(route)

    return routes


def remote_prefixes(topology, domain):
    prefixes = set()
    _, hosts_by_router = topology_indexes(topology)
    for router in topology["routers"]:
        if router_domain(router) != domain:
            continue
        prefixes.add(sid_prefix(router))
        for host in hosts_by_router.get(router["name"], []):
            prefixes.add(host_prefix(host))
    return prefixes


def load_or_build_topology(args):
    if args.topology_file:
        return load_topology_file(args.topology_file)
    return build_topology_from_template(
        args.routers,
        args.hosts_per_router,
        args.topology,
        grpc_base=args.grpc_base,
        device_id_base=args.device_id_base,
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ip", default="127.0.0.1",
                        help="Address ONOS uses to reach simple_switch_grpc")
    parser.add_argument("--topology-file",
                        help="Expanded topology JSON generated by build_topology.py")
    parser.add_argument("--routers", type=int, default=2)
    parser.add_argument("--hosts-per-router", type=int, default=1)
    parser.add_argument("--topology", choices=("linear", "ring", "mesh"),
                        default="linear")
    parser.add_argument("--grpc-base", type=int, default=9559)
    parser.add_argument("--device-id-base", type=int, default=0)
    parser.add_argument("--driver", default="bmv2")
    parser.add_argument("--pipeconf", default=DEFAULT_PIPECONF)
    parser.add_argument("--domain",
                        help="Generate netcfg for one router domain only")
    parser.add_argument("--output", default="netcfg.json")
    args = parser.parse_args()

    try:
        topology = load_or_build_topology(args)
    except ValueError as exc:
        raise SystemExit(str(exc))

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        netcfg = build_netcfg(topology, args)
    except ValueError as exc:
        raise SystemExit(str(exc))
    output.write_text(json.dumps(netcfg, indent=2) + "\n")
    print(f"Wrote {output}")


if __name__ == "__main__":
    main()
