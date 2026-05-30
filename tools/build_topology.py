#!/usr/bin/env python3
"""Build expanded topology JSON for ONOS netcfg and Mininet."""

import argparse
import ipaddress
import json
import re
from pathlib import Path


DEFAULT_GRPC_BASE = 9559
DEFAULT_THRIFT_BASE = 9090
DEFAULT_DEVICE_ID_BASE = 0
DEFAULT_CPU_PORT = 255
DEFAULT_IPV6_PREFIX = "2001"
DEFAULT_DOMAIN = "default"
TOPOLOGY_VERSION = 1
NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_-]*$")


def router_mac(router_id):
    suffix = f"{router_id:04x}"
    return f"00:aa:00:00:{suffix[:2]}:{suffix[2:]}"


def host_mac(router_index, host_index):
    return f"00:04:00:00:{router_index:02x}:{host_index:02x}"


def require_name(value, field):
    if not isinstance(value, str) or not NAME_RE.match(value):
        raise ValueError(
            f"{field} must match {NAME_RE.pattern}; got {value!r}")
    return value


def require_optional_name(value, field, default):
    if value is None:
        return default
    return require_name(value, field)


def require_int(value, field, minimum=0):
    if not isinstance(value, int) or value < minimum:
        raise ValueError(f"{field} must be an integer >= {minimum}; got {value!r}")
    return value


def require_optional_int(value, field, minimum=0):
    if value is None:
        return None
    return require_int(value, field, minimum)


def require_unique(items, field):
    seen = set()
    for item in items:
        if item in seen:
            raise ValueError(f"Duplicate {field}: {item}")
        seen.add(item)


def normalize_ip_prefix(value, field):
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"{field} must be an IPv6 address with prefix, got {value!r}")
    try:
        ipaddress.IPv6Interface(value)
    except ValueError as exc:
        raise ValueError(
            f"{field} must be an IPv6 address with prefix, got {value!r}") from exc
    return value


def require_object(value, field):
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    return value


def require_list(value, field, allow_empty=True):
    if value is None and allow_empty:
        return []
    if not isinstance(value, list):
        raise ValueError(f"{field} must be a list")
    if not allow_empty and not value:
        raise ValueError(f"{field} must be a non-empty list")
    return value


def normalize_mac(value, field):
    if value is None:
        return None
    if not isinstance(value, str) or not re.match(
            r"^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$", value):
        raise ValueError(f"{field} must be a MAC address, got {value!r}")
    return value.lower()


def normalize_bandwidth(value, field):
    if value is None:
        return None
    if not isinstance(value, (int, float)) or value <= 0:
        raise ValueError(f"{field} must be a number greater than 0; got {value!r}")
    return value


def normalize_delay(value, field):
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string; got {value!r}")
    return value


def get_default(defaults, cli_value, key, fallback):
    if cli_value is not None:
        return cli_value
    return defaults.get(key, fallback)


def template_router_names(num_routers):
    return [f"r{router}" for router in range(1, num_routers + 1)]


def template_links(num_routers, topology):
    links = []
    if topology == "linear":
        for router in range(1, num_routers):
            links.append({"left": f"r{router}", "right": f"r{router + 1}"})
    elif topology == "ring":
        for router in range(1, num_routers + 1):
            links.append({
                "left": f"r{router}",
                "right": f"r{router % num_routers + 1}",
            })
    elif topology == "mesh":
        for router in range(1, num_routers + 1):
            for distance in (1, 2):
                if router + distance <= num_routers:
                    links.append({
                        "left": f"r{router}",
                        "right": f"r{router + distance}",
                    })
    else:
        raise ValueError(f"Unsupported topology: {topology}")
    return links


def build_template_source(num_routers, hosts_per_router, topology):
    if num_routers < 1:
        raise ValueError("--routers must be >= 1")
    if hosts_per_router < 1:
        raise ValueError("--hosts-per-router must be >= 1")
    routers = [{"name": name} for name in template_router_names(num_routers)]
    hosts = []
    global_host_id = 1
    for router_name in template_router_names(num_routers):
        for _ in range(hosts_per_router):
            hosts.append({"name": f"h{global_host_id}", "router": router_name})
            global_host_id += 1
    return {
        "version": TOPOLOGY_VERSION,
        "name": f"{topology}-{num_routers}r-{hosts_per_router}h",
        "routers": routers,
        "links": template_links(num_routers, topology),
        "hosts": hosts,
    }


def parse_link_endpoint(value, field):
    if isinstance(value, str):
        return require_name(value, field), None
    if isinstance(value, dict):
        router = require_name(value.get("router"), f"{field}.router")
        port = require_optional_int(value.get("port"), f"{field}.port", 1)
        return router, port
    raise ValueError(f"{field} must be a router name or endpoint object")


def allocate_router_port(router, next_port, used_ports, requested, field):
    if requested is not None:
        port = require_int(requested, field, 1)
        if port in used_ports[router]:
            raise ValueError(f"Duplicate port {router}/{port}")
        used_ports[router].add(port)
        next_port[router] = max(next_port[router], port + 1)
        return port

    port = next_port[router]
    while port in used_ports[router]:
        port += 1
    used_ports[router].add(port)
    next_port[router] = port + 1
    return port


def expand_topology(source, args=None):
    args = args or argparse.Namespace()
    require_object(source, "topology")
    if source.get("version", TOPOLOGY_VERSION) != TOPOLOGY_VERSION:
        raise ValueError(
            f"Unsupported source topology version: {source.get('version')!r}")
    defaults = source.get("defaults") or {}
    require_object(defaults, "defaults")
    grpc_base = require_int(get_default(
        defaults, getattr(args, "grpc_base", None), "grpcBase", DEFAULT_GRPC_BASE),
        "defaults.grpcBase")
    thrift_base = require_int(get_default(
        defaults, getattr(args, "thrift_base", None), "thriftBase", DEFAULT_THRIFT_BASE),
        "defaults.thriftBase")
    device_id_base = require_int(get_default(
        defaults, getattr(args, "device_id_base", None), "deviceIdBase",
        DEFAULT_DEVICE_ID_BASE), "defaults.deviceIdBase")
    cpu_port = require_int(get_default(
        defaults, getattr(args, "cpu_port", None), "cpuPort", DEFAULT_CPU_PORT),
        "defaults.cpuPort")
    ipv6_prefix = defaults.get("ipv6Prefix", DEFAULT_IPV6_PREFIX)
    if not isinstance(ipv6_prefix, str) or not ipv6_prefix:
        raise ValueError("defaults.ipv6Prefix must be a non-empty string")

    router_sources = require_list(source.get("routers"), "routers", allow_empty=False)
    router_sources = [require_object(router, f"routers[{idx}]")
                      for idx, router in enumerate(router_sources)]
    router_names = [require_name(router.get("name"), "routers[].name")
                    for router in router_sources]
    require_unique(router_names, "router name")
    router_index = {name: idx for idx, name in enumerate(router_names)}

    routers = []
    used_device_ids = []
    used_grpc_ports = []
    used_thrift_ports = []
    for idx, router in enumerate(router_sources):
        name = router_names[idx]
        device_id = require_int(router.get("deviceId", device_id_base + idx),
                                f"routers[{idx}].deviceId")
        grpc_port = require_int(router.get("grpcPort", grpc_base + idx),
                                f"routers[{idx}].grpcPort")
        thrift_port = require_int(router.get("thriftPort", thrift_base + idx),
                                  f"routers[{idx}].thriftPort")
        my_station_mac = normalize_mac(
            router.get("myStationMac", router_mac(idx + 1)),
            f"routers[{idx}].myStationMac")
        my_sid = router.get("mySid", f"fc00:0:{idx + 1}::")
        if not isinstance(my_sid, str) or not my_sid:
            raise ValueError(f"routers[{idx}].mySid must be a non-empty string")
        routers.append({
            "name": name,
            "domain": require_optional_name(
                router.get("domain"), f"routers[{idx}].domain", DEFAULT_DOMAIN),
            "deviceId": device_id,
            "grpcPort": grpc_port,
            "thriftPort": thrift_port,
            "cpuPort": require_int(router.get("cpuPort", cpu_port),
                                   f"routers[{idx}].cpuPort"),
            "myStationMac": my_station_mac,
            "mySid": my_sid,
        })
        used_device_ids.append(device_id)
        used_grpc_ports.append(grpc_port)
        used_thrift_ports.append(thrift_port)

    require_unique(used_device_ids, "deviceId")
    require_unique(used_grpc_ports, "grpcPort")
    require_unique(used_thrift_ports, "thriftPort")

    next_port = {router: 1 for router in router_names}
    used_ports = {router: set() for router in router_names}
    links = []
    link_sources = require_list(source.get("links"), "links")
    link_sources = [require_object(link, f"links[{idx}]")
                    for idx, link in enumerate(link_sources)]
    for idx, link in enumerate(link_sources):
        left, left_endpoint_port = parse_link_endpoint(link.get("left"),
                                                       f"links[{idx}].left")
        right, right_endpoint_port = parse_link_endpoint(link.get("right"),
                                                         f"links[{idx}].right")
        if left not in router_index:
            raise ValueError(f"links[{idx}].left references unknown router {left}")
        if right not in router_index:
            raise ValueError(f"links[{idx}].right references unknown router {right}")
        left_port = link.get("leftPort", left_endpoint_port)
        right_port = link.get("rightPort", right_endpoint_port)
        left_port = allocate_router_port(left, next_port, used_ports, left_port,
                                         f"links[{idx}].leftPort")
        right_port = allocate_router_port(right, next_port, used_ports, right_port,
                                          f"links[{idx}].rightPort")
        expanded = {
            "left": {"router": left, "port": left_port},
            "right": {"router": right, "port": right_port},
        }
        if "bw" in link:
            expanded["bw"] = normalize_bandwidth(link["bw"], f"links[{idx}].bw")
        if "delay" in link:
            expanded["delay"] = normalize_delay(link["delay"], f"links[{idx}].delay")
        links.append(expanded)

    host_sources = require_list(source.get("hosts"), "hosts")
    host_sources = [require_object(host, f"hosts[{idx}]")
                    for idx, host in enumerate(host_sources)]
    host_names = [require_name(host.get("name"), "hosts[].name")
                  for host in host_sources]
    require_unique(host_names, "host name")

    per_router_host_count = {router: 0 for router in router_names}
    hosts = []
    for idx, host in enumerate(host_sources):
        name = host_names[idx]
        router = require_name(host.get("router"), f"hosts[{idx}].router")
        if router not in router_index:
            raise ValueError(f"hosts[{idx}].router references unknown router {router}")
        per_router_host_count[router] += 1
        router_number = router_index[router] + 1
        host_number = per_router_host_count[router]
        router_port = allocate_router_port(
            router, next_port, used_ports, host.get("routerPort"),
            f"hosts[{idx}].routerPort")
        ip = normalize_ip_prefix(
            host.get("ip", f"{ipv6_prefix}:{router_number}:{host_number}::10/64"),
            f"hosts[{idx}].ip")
        gateway = normalize_ip_prefix(
            host.get("gateway", f"{ipv6_prefix}:{router_number}:{host_number}::254/64"),
            f"hosts[{idx}].gateway")
        mac = normalize_mac(
            host.get("mac", host_mac(router_index[router], host_number - 1)),
            f"hosts[{idx}].mac")
        hosts.append({
            "name": name,
            "router": router,
            "routerPort": router_port,
            "mac": mac,
            "ip": ip,
            "gateway": gateway,
        })

    return {
        "version": TOPOLOGY_VERSION,
        "name": source.get("name", "custom"),
        "defaults": {
            "grpcBase": grpc_base,
            "thriftBase": thrift_base,
            "deviceIdBase": device_id_base,
            "cpuPort": cpu_port,
            "ipv6Prefix": ipv6_prefix,
        },
        "routers": routers,
        "links": links,
        "hosts": hosts,
    }


def build_topology_from_template(num_routers, hosts_per_router, topology,
                                 grpc_base=DEFAULT_GRPC_BASE,
                                 thrift_base=DEFAULT_THRIFT_BASE,
                                 device_id_base=DEFAULT_DEVICE_ID_BASE,
                                 cpu_port=DEFAULT_CPU_PORT):
    source = build_template_source(num_routers, hosts_per_router, topology)
    args = argparse.Namespace(
        grpc_base=grpc_base,
        thrift_base=thrift_base,
        device_id_base=device_id_base,
        cpu_port=cpu_port,
    )
    return expand_topology(source, args)


def load_topology_file(path):
    with Path(path).open() as fp:
        topology = json.load(fp)
    if topology.get("version") != TOPOLOGY_VERSION:
        raise ValueError(
            f"Unsupported topology version: {topology.get('version')!r}")
    return topology


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config",
                        help="Custom source topology JSON to expand")
    parser.add_argument("--routers", type=int, default=2)
    parser.add_argument("--hosts-per-router", type=int, default=1)
    parser.add_argument("--topology", choices=("linear", "ring", "mesh"),
                        default="linear")
    parser.add_argument("--grpc-base", type=int, default=None)
    parser.add_argument("--thrift-base", type=int, default=None)
    parser.add_argument("--device-id-base", type=int, default=None)
    parser.add_argument("--cpu-port", type=int, default=None)
    parser.add_argument("--output", default="topology.json")
    args = parser.parse_args()

    if args.config:
        with Path(args.config).open() as fp:
            source = json.load(fp)
    else:
        source = build_template_source(
            args.routers, args.hosts_per_router, args.topology)

    topology = expand_topology(source, args)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(topology, indent=2) + "\n")
    print(f"Wrote {output}")


if __name__ == "__main__":
    try:
        main()
    except ValueError as exc:
        raise SystemExit(str(exc))
