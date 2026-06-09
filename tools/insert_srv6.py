#!/usr/bin/env python3
"""Insert an SRv6 transit policy using topology node names."""

import argparse
import ipaddress
import json
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = ROOT_DIR / "target" / "srv6-insert"
DEFAULT_TOPOLOGY_GLOB = "topology-*.json"
DEFAULT_TOPOLOGY_DIRS = (
    ROOT_DIR / "topologies" / "generated",
    ROOT_DIR / "target" / "env",
)
MIN_SRV6_ENTRIES = 2
MAX_SRV6_ENTRIES = 6
DEFAULT_DOMAIN = "default"


class Srv6InsertError(RuntimeError):
    """Raised when the SRv6 policy cannot be installed."""


def log(message):
    print(message, flush=True)


def load_topology(path):
    try:
        with path.open() as fp:
            topology = json.load(fp)
    except OSError as exc:
        raise Srv6InsertError(f"Cannot read topology file {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise Srv6InsertError(f"Invalid topology JSON in {path}: {exc}") from exc

    if not isinstance(topology, dict):
        raise Srv6InsertError(f"Topology file {path} must contain a JSON object")
    return topology


def load_domain_map(path):
    try:
        with path.open() as fp:
            mapping = json.load(fp)
    except OSError as exc:
        raise Srv6InsertError(f"Cannot read domain map file {path}: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise Srv6InsertError(f"Invalid domain map JSON in {path}: {exc}") from exc

    if not isinstance(mapping, dict):
        raise Srv6InsertError(f"Domain map file {path} must contain a JSON object")
    return mapping


def find_default_topology():
    candidates = []
    for topology_dir in DEFAULT_TOPOLOGY_DIRS:
        candidates.extend(topology_dir.glob(DEFAULT_TOPOLOGY_GLOB))
    if not candidates:
        raise Srv6InsertError(
            "No topology files found under topologies/generated or target/env. "
            "Run env/create_onos.sh first or pass --topology-file.")
    return max(candidates, key=lambda candidate: candidate.stat().st_mtime)


def topology_indexes(topology):
    routers = {}
    for router in topology.get("routers", []):
        name = router.get("name") if isinstance(router, dict) else None
        if name:
            routers[name] = router

    hosts = {}
    for host in topology.get("hosts", []):
        name = host.get("name") if isinstance(host, dict) else None
        if name:
            hosts[name] = host

    if not routers:
        raise Srv6InsertError("Topology does not define any routers")
    if not hosts:
        raise Srv6InsertError("Topology does not define any hosts")
    return routers, hosts


def normalize_ipv6(value, field):
    try:
        return str(ipaddress.IPv6Address(value))
    except ValueError as exc:
        raise Srv6InsertError(f"{field} must be a valid IPv6 address: {value}") from exc


def strip_ipv6_prefix(value, field):
    try:
        return str(ipaddress.IPv6Interface(value).ip)
    except ValueError as exc:
        raise Srv6InsertError(
            f"{field} must be a valid IPv6 address or prefix: {value}") from exc


def resolve_policy(topology, ingress, path_nodes):
    routers, hosts = topology_indexes(topology)

    if ingress not in routers:
        raise Srv6InsertError(
            f"Ingress node {ingress!r} is not a router in this topology")

    if len(path_nodes) < MIN_SRV6_ENTRIES or len(path_nodes) > MAX_SRV6_ENTRIES:
        raise Srv6InsertError(
            "SRv6 segment list must contain 2 to 6 entries after the ingress "
            f"router; got {len(path_nodes)}")

    sid_nodes = path_nodes[:-1]
    destination = path_nodes[-1]

    segments = []
    for node in sid_nodes:
        router = routers.get(node)
        if router is None:
            raise Srv6InsertError(
                f"Segment node {node!r} is not a router in this topology")
        sid = router.get("mySid")
        if not sid:
            raise Srv6InsertError(f"Router {node!r} does not define mySid")
        segments.append(normalize_ipv6(sid, f"Router {node!r} mySid"))

    host = hosts.get(destination)
    if host is not None:
        host_ip = host.get("ip")
        if not host_ip:
            raise Srv6InsertError(f"Host {destination!r} does not define ip")
        segments.append(strip_ipv6_prefix(host_ip, f"Host {destination!r} ip"))
    else:
        segments.append(normalize_ipv6(destination, "Destination"))

    return {
        "device": f"device:bmv2:{ingress}",
        "domain": routers[ingress].get("domain", DEFAULT_DOMAIN),
        "segments": segments,
    }


def apply_domain_env(env, domain_map, domain):
    if not domain_map:
        return env
    if domain not in domain_map:
        raise Srv6InsertError(
            f"Domain {domain!r} is not present in domain map; available: "
            f"{', '.join(sorted(domain_map))}")
    config = domain_map[domain]
    if not isinstance(config, dict):
        raise Srv6InsertError(f"Domain map entry for {domain!r} must be an object")

    mapped_env = env.copy()
    for key, value in config.items():
        if not isinstance(value, str):
            raise Srv6InsertError(
                f"Domain map value {domain}.{key} must be a string")
        mapped_env[key] = value
    return mapped_env


def command_log_path(output_dir, command):
    safe_name = re.sub(r"[^A-Za-z0-9_.-]+", "_", command).strip("_")
    if not safe_name:
        safe_name = "onos-cli"
    return output_dir / f"{safe_name}.log"


def run_logged(cmd, log_path, env):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log("+ {}".format(" ".join(shlex.quote(part) for part in cmd)))
    with log_path.open("w") as fp:
        proc = subprocess.run(
            cmd,
            cwd=str(ROOT_DIR),
            env=env,
            stdout=fp,
            stderr=subprocess.STDOUT,
            text=True)
    if proc.returncode != 0:
        raise Srv6InsertError(
            f"Command failed with exit code {proc.returncode}. See {log_path}")


def build_onos_cli_command(command, env):
    custom = env.get("ONOS_CLI_CMD")
    if custom:
        return shlex.split(custom) + [command]

    docker = shlex.split(env.get("DOCKER_CMD", "docker"))
    container = env.get("ONOS_CONTAINER", "onos")
    script = r'''
cmd="$1"
for candidate in \
  /root/onos/apache-karaf-4.2.9/bin/client \
  /opt/onos/apache-karaf-4.2.9/bin/client \
  /onos/apache-karaf-4.2.9/bin/client; do
  if [ -x "$candidate" ]; then
    printf '%s\n' "$cmd" | "$candidate" -h 127.0.0.1 -a 8101 -u onos -p rocks -b
    exit $?
  fi
done
echo "Karaf client not found; set ONOS_CLI_CMD to override" >&2
exit 127
'''
    return docker + ["exec", container, "sh", "-lc", script, "sh", command]


def onos_cli(command, output_dir, env, dry_run):
    if dry_run:
        log(command)
        return

    cmd = build_onos_cli_command(command, env)
    run_logged(cmd, command_log_path(output_dir, command), env)


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description=(
            "Insert an SRv6 transit policy using topology names. "
            "Example: tools/insert_srv6.py r1 r2 r4 h3"))
    parser.add_argument(
        "--topology-file",
        help=(
            "Expanded topology JSON file. Defaults to the newest "
            "topologies/generated/topology-*.json or target/env/topology-*.json"))
    parser.add_argument(
        "--output-dir",
        default=str(DEFAULT_OUTPUT_DIR),
        help=f"Directory for ONOS CLI logs. Default: {DEFAULT_OUTPUT_DIR}")
    parser.add_argument(
        "--domain-map",
        help=(
            "JSON object mapping topology domain names to environment overrides, "
            "for example ONOS_CONTAINER or ONOS_CLI_CMD"))
    parser.add_argument(
        "--clear",
        action="store_true",
        help="Clear existing SRv6 transit rules on the ingress device before inserting")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the resolved ONOS CLI commands without executing them")
    parser.add_argument(
        "nodes",
        nargs="+",
        help=(
            "Ingress router followed by 2 to 6 SRv6 entries. "
            "Middle entries are router names; the final entry is a host name or IPv6 address."))
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv or sys.argv[1:])
    if len(args.nodes) < MIN_SRV6_ENTRIES + 1 or len(args.nodes) > MAX_SRV6_ENTRIES + 1:
        raise Srv6InsertError(
            "Expected ingress router plus 2 to 6 SRv6 entries, for example: "
            "tools/insert_srv6.py r1 r2 r3 r4 r5 r6 h7")

    topology_path = Path(args.topology_file) if args.topology_file else find_default_topology()
    if not topology_path.is_absolute():
        topology_path = (ROOT_DIR / topology_path).resolve()
    topology = load_topology(topology_path)
    domain_map = None
    if args.domain_map:
        domain_map_path = Path(args.domain_map)
        if not domain_map_path.is_absolute():
            domain_map_path = (ROOT_DIR / domain_map_path).resolve()
        domain_map = load_domain_map(domain_map_path)

    policy = resolve_policy(topology, args.nodes[0], args.nodes[1:])
    insert_command = "srv6-insert {} {}".format(
        policy["device"], " ".join(policy["segments"]))
    clear_command = f"srv6-clear {policy['device']}"

    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = (ROOT_DIR / output_dir).resolve()

    if args.dry_run:
        log(f"topology: {topology_path}")
        if domain_map:
            log(f"domain: {policy['domain']}")
        if args.clear:
            onos_cli(clear_command, output_dir,
                     apply_domain_env(os.environ.copy(), domain_map, policy["domain"]),
                     dry_run=True)
        onos_cli(insert_command, output_dir,
                 apply_domain_env(os.environ.copy(), domain_map, policy["domain"]),
                 dry_run=True)
        return 0

    env = apply_domain_env(os.environ.copy(), domain_map, policy["domain"])
    if args.clear:
        onos_cli(clear_command, output_dir, env, dry_run=False)
    onos_cli(insert_command, output_dir, env, dry_run=False)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Srv6InsertError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
