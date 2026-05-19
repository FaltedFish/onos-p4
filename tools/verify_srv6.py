#!/usr/bin/env python3
"""Run an end-to-end SRv6 smoke test against ONOS and Mininet."""

import argparse
import base64
import json
import os
import shutil
import shlex
import signal
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from ipaddress import IPv6Address
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = ROOT_DIR / "target" / "srv6-verify"

ROUTERS = 4
HOSTS_PER_ROUTER = 1
TOPOLOGY = "ring"

R1_DEVICE = "device:bmv2:r1"
EXPECTED_DEVICES = [
    "device:bmv2:r1",
    "device:bmv2:r2",
    "device:bmv2:r3",
    "device:bmv2:r4",
]

H1_IP = "2001:1:1::10"
H4_IP = "2001:4:1::10"
R2_SID = "fc00:0:2::"
R3_SID = "fc00:0:3::"


class VerifyError(RuntimeError):
    """Raised when the SRv6 verification cannot proceed or fails."""


def log(message):
    print(message, flush=True)


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w") as fp:
        json.dump(data, fp, indent=2, sort_keys=True)
        fp.write("\n")


def run_logged(cmd, log_path, env=None, check=True):
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
    if check and proc.returncode != 0:
        raise VerifyError(
            "Command failed with exit code {}. See {}".format(
                proc.returncode, log_path))
    return proc.returncode


def start_logged(cmd, log_path, env=None, new_session=False, stdin=None):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log("+ {}".format(" ".join(shlex.quote(part) for part in cmd)))
    fp = log_path.open("w")
    kwargs = {}
    if new_session:
        kwargs["start_new_session"] = True
    proc = subprocess.Popen(
        cmd,
        cwd=str(ROOT_DIR),
        env=env,
        stdout=fp,
        stderr=subprocess.STDOUT,
        stdin=stdin,
        **kwargs,
        text=True)
    proc.started_new_session = new_session
    return proc, fp


def terminate_process(proc, logfile):
    if proc.poll() is not None:
        return
    try:
        if getattr(proc, "started_new_session", False):
            os.killpg(proc.pid, signal.SIGTERM)
        else:
            proc.terminate()
    except PermissionError:
        try:
            subprocess.run(
                ["sudo", "-n", "kill", "-TERM", str(proc.pid)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False)
        except OSError:
            pass
    try:
        proc.wait(timeout=10)
    except (subprocess.TimeoutExpired, PermissionError):
        try:
            subprocess.run(
                ["sudo", "-n", "kill", "-TERM", str(proc.pid)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False)
        except OSError:
            pass
    if proc.poll() is None:
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
    if proc.poll() is None:
        if getattr(proc, "started_new_session", False):
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except PermissionError:
                pass
        else:
            try:
                proc.kill()
            except PermissionError:
                pass
        try:
            subprocess.run(
                ["sudo", "-n", "kill", "-KILL", str(proc.pid)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False)
        except OSError:
            pass
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            pass
    if logfile:
        logfile.close()


class OnosRest:
    def __init__(self, url, auth, timeout):
        self.url = url.rstrip("/")
        self.timeout = timeout
        token = base64.b64encode(auth.encode("utf-8")).decode("ascii")
        self.headers = {
            "Authorization": "Basic {}".format(token),
            "Accept": "application/json",
        }

    def get_json(self, path):
        req = urllib.request.Request(
            "{}/onos/v1/{}".format(self.url, path.lstrip("/")),
            headers=self.headers)
        with urllib.request.urlopen(req, timeout=self.timeout) as response:
            body = response.read().decode("utf-8")
        return json.loads(body)


def retry_until(name, timeout, interval, fn):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            result = fn()
            if result:
                return result
        except (OSError, urllib.error.URLError, urllib.error.HTTPError,
                json.JSONDecodeError, VerifyError) as exc:
            last_error = exc
        time.sleep(interval)
    if last_error:
        raise VerifyError("{} timed out: {}".format(name, last_error))
    raise VerifyError("{} timed out".format(name))


def snapshot_rest(rest, output_dir, label):
    snapshots = {}
    for endpoint in ("devices", "hosts", "links", "flows", "groups"):
        try:
            data = rest.get_json(endpoint)
            write_json(output_dir / "onos-{}-{}.json".format(label, endpoint), data)
            snapshots[endpoint] = data
        except Exception as exc:
            snapshots[endpoint] = {"error": str(exc)}
    return snapshots


def devices_are_available(rest):
    data = rest.get_json("devices")
    devices = {device.get("id"): device for device in data.get("devices", [])}
    for device_id in EXPECTED_DEVICES:
        if device_id not in devices:
            return False
        if devices[device_id].get("available") is not True:
            return False
    return data


def expected_hosts_are_present(rest):
    data = rest.get_json("hosts")
    text = json.dumps(data)
    if H1_IP not in text or H4_IP not in text:
        return False
    if len(data.get("hosts", [])) < ROUTERS * HOSTS_PER_ROUTER:
        return False
    return data


def flow_list(rest):
    data = rest.get_json("flows")
    return data.get("flows", []), data


def flow_contains(flow, device_id, *needles):
    text = json.dumps(flow, sort_keys=True)
    return device_id in text and all(needle in text for needle in needles)


def flow_is_added(flow):
    return flow.get("state") == "ADDED"


def my_sid_flows_are_present(rest):
    flows, data = flow_list(rest)
    for device_id in EXPECTED_DEVICES:
        if not any(flow_is_added(flow)
                   and flow_contains(flow, device_id, "srv6_my_sid")
                   for flow in flows):
            return False
    return data


def srv6_transit_flow_is_present(rest):
    flows, data = flow_list(rest)
    if not any(flow_is_added(flow)
               and flow_contains(flow, R1_DEVICE, "srv6_transit", "srv6_t_insert_3")
               for flow in flows):
        return False
    return data


def onos_cli(command, output_dir, env):
    custom = env.get("ONOS_CLI_CMD")
    if custom:
        cmd = shlex.split(custom) + [command]
        input_text = None
    else:
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
        cmd = docker + ["exec", container, "sh", "-lc", script, "sh", command]
        input_text = None

    safe_name = (
        command.replace(" ", "_")
        .replace(":", "_")
        .replace("/", "_")
        .replace(".", "_"))
    log_path = output_dir / "{}.log".format(safe_name)
    return run_logged(cmd, log_path, env=env, check=True)


def build_batch_file(output_dir):
    batch_path = output_dir / "mininet-batch.json"
    ready_file = output_dir / "mininet.ready.json"
    start_file = output_dir / "mininet.start"
    result_file = output_dir / "mininet-result.json"

    batch = {
        "readyFile": str(ready_file),
        "startFile": str(start_file),
        "startTimeoutSeconds": 240,
        "captureWarmupSeconds": 1,
        "settleSeconds": 1,
        "tcpdump": [
            {
                "name": "r1-r2",
                "node": "r1",
                "interface": "r1-eth1",
                "output": str(output_dir / "r1-r2.pcap"),
                "filter": "ip6",
            },
            {
                "name": "r2-r3",
                "node": "r2",
                "interface": "r2-eth2",
                "output": str(output_dir / "r2-r3.pcap"),
                "filter": "ip6",
            },
            {
                "name": "r3-r4",
                "node": "r3",
                "interface": "r3-eth2",
                "output": str(output_dir / "r3-r4.pcap"),
                "filter": "ip6",
            },
            {
                "name": "r1-r4-direct",
                "node": "r1",
                "interface": "r1-eth2",
                "output": str(output_dir / "r1-r4-direct.pcap"),
                "filter": "ip6",
                "allowEmpty": True,
            },
        ],
        "commands": [
            {
                "name": "h1-ping-h2-baseline",
                "node": "h1",
                "cmd": "ping6 -c 3 -W 2 2001:2:1::10",
                "expectReturnCode": 0,
            },
            {
                "name": "h1-ping-h4",
                "node": "h1",
                "cmd": "ping6 -c 3 -W 2 {}".format(H4_IP),
                "expectReturnCode": 0,
            },
        ],
    }
    write_json(batch_path, batch)
    return batch_path, ready_file, start_file, result_file


def read_pcap(path):
    data = Path(path).read_bytes()
    if len(data) < 24:
        return []

    magic = data[:4]
    if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1"):
        endian = "<"
    elif magic in (b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
        endian = ">"
    else:
        raise VerifyError("Unsupported pcap magic in {}".format(path))

    linktype = struct.unpack(endian + "I", data[20:24])[0]
    if linktype != 1:
        raise VerifyError("Unsupported pcap link type {} in {}".format(
            linktype, path))

    packets = []
    offset = 24
    while offset + 16 <= len(data):
        _ts_sec, _ts_usec, incl_len, _orig_len = struct.unpack(
            endian + "IIII", data[offset:offset + 16])
        offset += 16
        frame = data[offset:offset + incl_len]
        offset += incl_len
        if len(frame) >= 14:
            parsed = parse_ipv6_frame(frame)
            if parsed:
                packets.append(parsed)
    return packets


def parse_ipv6_frame(frame):
    offset = 12
    ether_type = struct.unpack("!H", frame[offset:offset + 2])[0]
    offset = 14
    while ether_type in (0x8100, 0x88a8):
        if len(frame) < offset + 4:
            return None
        ether_type = struct.unpack("!H", frame[offset + 2:offset + 4])[0]
        offset += 4

    if ether_type != 0x86DD or len(frame) < offset + 40:
        return None

    first = frame[offset]
    if first >> 4 != 6:
        return None

    next_header = frame[offset + 6]
    src = str(IPv6Address(frame[offset + 8:offset + 24]))
    dst = str(IPv6Address(frame[offset + 24:offset + 40]))
    cursor = offset + 40
    srh = None
    final_next_header = next_header

    if next_header == 43 and len(frame) >= cursor + 8:
        routing_next = frame[cursor]
        hdr_ext_len = frame[cursor + 1]
        routing_type = frame[cursor + 2]
        segments_left = frame[cursor + 3]
        last_entry = frame[cursor + 4]
        total_len = (hdr_ext_len + 1) * 8
        segment_count = last_entry + 1
        segments = []
        segment_offset = cursor + 8
        for idx in range(segment_count):
            start = segment_offset + idx * 16
            end = start + 16
            if end <= len(frame):
                segments.append(str(IPv6Address(frame[start:end])))
        srh = {
            "nextHeader": routing_next,
            "hdrExtLen": hdr_ext_len,
            "routingType": routing_type,
            "segmentsLeft": segments_left,
            "lastEntry": last_entry,
            "segments": segments,
            "headerLength": total_len,
        }
        final_next_header = routing_next

    return {
        "src": src,
        "dst": dst,
        "nextHeader": next_header,
        "finalNextHeader": final_next_header,
        "srh": srh,
    }


def count_packets(packets, predicate):
    return sum(1 for packet in packets if predicate(packet))


def validate_pcaps(output_dir):
    pcaps = {
        "r1-r2": read_pcap(output_dir / "r1-r2.pcap"),
        "r2-r3": read_pcap(output_dir / "r2-r3.pcap"),
        "r3-r4": read_pcap(output_dir / "r3-r4.pcap"),
        "r1-r4-direct": read_pcap(output_dir / "r1-r4-direct.pcap"),
    }

    expected_segments = [H4_IP, R3_SID, R2_SID]

    checks = {
        "r1-r2_srh_to_r2": count_packets(
            pcaps["r1-r2"],
            lambda packet: packet["src"] == H1_IP
            and packet["dst"] == R2_SID
            and packet["srh"]
            and packet["srh"]["routingType"] == 4
            and packet["srh"]["segmentsLeft"] == 2
            and packet["srh"]["segments"] == expected_segments) > 0,
        "r2-r3_srh_to_r3": count_packets(
            pcaps["r2-r3"],
            lambda packet: packet["src"] == H1_IP
            and packet["dst"] == R3_SID
            and packet["srh"]
            and packet["srh"]["routingType"] == 4
            and packet["srh"]["segmentsLeft"] == 1
            and packet["srh"]["segments"] == expected_segments) > 0,
        "r3-r4_popped_to_h4": count_packets(
            pcaps["r3-r4"],
            lambda packet: packet["src"] == H1_IP
            and packet["dst"] == H4_IP
            and packet["nextHeader"] == 58
            and not packet["srh"]) > 0,
        "r1-r4_no_direct_h1_to_h4": count_packets(
            pcaps["r1-r4-direct"],
            lambda packet: packet["src"] == H1_IP
            and (packet["dst"] in (H4_IP, R2_SID, R3_SID)
                 or packet["srh"])) == 0,
    }

    summary = {
        "checks": checks,
        "packetCounts": {name: len(packets) for name, packets in pcaps.items()},
        "samples": {
            name: packets[:5]
            for name, packets in pcaps.items()
        },
    }
    write_json(output_dir / "pcap-summary.json", summary)

    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise VerifyError("PCAP validation failed: {}".format(", ".join(failed)))
    return summary


def wait_for_file(path, proc, timeout, description):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if path.exists():
            return
        if proc.poll() is not None:
            raise VerifyError(
                "{} did not appear before Mininet exited with code {}".format(
                    description, proc.returncode))
        time.sleep(0.5)
    raise VerifyError("Timed out waiting for {}".format(description))


def wait_for_json_file(path, proc, timeout, description):
    wait_for_file(path, proc, timeout, description)
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        try:
            return json.loads(path.read_text())
        except (OSError, ValueError) as exc:
            last_error = exc
            if proc.poll() is not None:
                raise VerifyError(
                    "{} was not readable before Mininet exited with code {}: {}".format(
                        description, proc.returncode, last_error))
            time.sleep(0.2)
    raise VerifyError("Timed out reading {}: {}".format(description, last_error))


def prepare_env(args, output_dir, batch_path, result_file):
    env = os.environ.copy()
    env.update({
        "ROUTERS": str(ROUTERS),
        "HOSTS_PER_ROUTER": str(HOSTS_PER_ROUTER),
        "TOPOLOGY": TOPOLOGY,
    })
    if args.skip_build:
        env["BUILD_APP"] = "0"
        env["BUILD_P4"] = "0"
    env["MININET_BATCH_JSON"] = str(batch_path)
    env["MININET_BATCH_RESULT"] = str(result_file)
    env["MININET_NO_CLI"] = "1"
    env["MININET_KEEP_RUNNING_AFTER_BATCH"] = "1"
    env.setdefault("PCAP_DUMP", "0")
    env.setdefault("ONOS_URL", "http://127.0.0.1:8181")
    env.setdefault("ONOS_AUTH", "onos:rocks")
    env.setdefault("ONOS_CONTAINER", "onos")
    env.setdefault("DOCKER_CMD", "docker")
    env.setdefault("RECREATE_ONOS", "1")
    if "SUDO_PASSWORD" in env:
        env.setdefault("SUDO_CMD", "sudo -S")
    env["SRV6_VERIFY_OUTPUT_DIR"] = str(output_dir)
    return env


def sudo_stdin(env):
    password = env.get("SUDO_PASSWORD")
    if not password:
        return None
    return subprocess.PIPE


def feed_sudo_password(proc, env):
    password = env.get("SUDO_PASSWORD")
    if not password or proc.stdin is None:
        return
    repeat = int(env.get("SUDO_PASSWORD_REPEAT", "8"))
    try:
        proc.stdin.write((password + "\n") * repeat)
        proc.stdin.flush()
        proc.stdin.close()
    except BrokenPipeError:
        pass


def run_create_onos(args, output_dir, env):
    fd, tmp_name = tempfile.mkstemp(
        prefix="srv6-create-onos-", suffix=".log")
    os.close(fd)
    tmp_log = Path(tmp_name)
    try:
        return run_logged(["./env/create_onos.sh"], tmp_log, env=env)
    finally:
        output_dir.mkdir(parents=True, exist_ok=True)
        if tmp_log.exists():
            shutil.copyfile(tmp_log, output_dir / "create_onos.log")
            tmp_log.unlink()


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR,
                        help="Directory for logs, pcap files, and JSON results")
    parser.add_argument("--skip-build", action="store_true",
                        help="Reuse the existing OAR/P4 artifacts")
    parser.add_argument("--keep-env", action="store_true",
                        help="Leave Mininet running if setup fails before traffic starts")
    parser.add_argument("--onos-timeout", type=int, default=240,
                        help="Seconds to wait for ONOS devices/flows")
    parser.add_argument("--mininet-ready-timeout", type=int, default=180,
                        help="Seconds to wait for Mininet to become ready")
    parser.add_argument("--mininet-run-timeout", type=int, default=120,
                        help="Seconds to wait for Mininet batch completion")
    parser.add_argument("--rest-timeout", type=int, default=5,
                        help="ONOS REST request timeout in seconds")
    return parser.parse_args()


def main():
    args = parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    for stale in (
            "mininet.ready.json",
            "mininet.start",
            "mininet-result.json",
            "mininet-result.normalized.json",
            "pcap-summary.json",
            "result.json",
            "r1-r2.pcap",
            "r2-r3.pcap",
            "r3-r4.pcap",
            "r1-r4-direct.pcap"):
        stale_path = output_dir / stale
        if stale_path.exists():
            stale_path.unlink()

    env = prepare_env(args, output_dir, Path("/dev/null"), Path("/dev/null"))
    rest = OnosRest(env["ONOS_URL"], env["ONOS_AUTH"], args.rest_timeout)

    mininet_proc = None
    mininet_log_fp = None
    final = {
        "passed": False,
        "outputDir": str(output_dir),
        "checks": [],
    }

    try:
        log("Preparing ONOS for 4-router SRv6 verification...")
        run_create_onos(args, output_dir, env)

        output_dir.mkdir(parents=True, exist_ok=True)
        for stale in (
                "mininet.ready.json",
                "mininet.start",
                "mininet-result.json",
                "mininet-result.normalized.json",
                "pcap-summary.json",
                "r1-r2.pcap",
                "r2-r3.pcap",
                "r3-r4.pcap",
                "r1-r4-direct.pcap"):
            stale_path = output_dir / stale
            if stale_path.exists():
                stale_path.unlink()

        batch_path, ready_file, start_file, result_file = build_batch_file(output_dir)
        env = prepare_env(args, output_dir, batch_path, result_file)

        log("Starting Mininet in batch mode...")
        mininet_proc, mininet_log_fp = start_logged(
            ["./env/create_mininet.sh"],
            output_dir / "create_mininet.log",
            env=env,
            stdin=sudo_stdin(env))
        feed_sudo_password(mininet_proc, env)

        wait_for_file(
            ready_file, mininet_proc, args.mininet_ready_timeout,
            "Mininet ready file")
        final["checks"].append("mininet-ready")

        retry_until(
            "ONOS devices available",
            args.onos_timeout, 2,
            lambda: devices_are_available(rest))
        final["checks"].append("devices-available")

        retry_until(
            "ONOS hosts present",
            args.onos_timeout, 2,
            lambda: expected_hosts_are_present(rest))
        final["checks"].append("hosts-present")

        retry_until(
            "SRv6 mySid flows present",
            args.onos_timeout, 2,
            lambda: my_sid_flows_are_present(rest))
        final["checks"].append("srv6-my-sid-flows")
        snapshot_rest(rest, output_dir, "before-srv6-insert")

        log("Installing SRv6 transit policy on r1...")
        onos_cli("srv6-clear {}".format(R1_DEVICE), output_dir, env)
        onos_cli(
            "srv6-insert {} {} {} {}".format(
                R1_DEVICE, R2_SID, R3_SID, H4_IP),
            output_dir, env)

        retry_until(
            "SRv6 transit flow present",
            args.onos_timeout, 2,
            lambda: srv6_transit_flow_is_present(rest))
        final["checks"].append("srv6-transit-flow")
        snapshot_rest(rest, output_dir, "after-srv6-insert")

        log("Starting captured traffic test...")
        start_file.write_text("start\n")

        batch_result = wait_for_json_file(
            result_file, mininet_proc, args.mininet_run_timeout,
            "Mininet batch result")

        if mininet_log_fp:
            mininet_log_fp.close()
            mininet_log_fp = None

        return_code = mininet_proc.poll()
        if return_code is not None and return_code != 0:
            raise VerifyError(
                "Mininet batch failed with exit code {}. See {}".format(
                    return_code, output_dir / "create_mininet.log"))

        write_json(output_dir / "mininet-result.normalized.json", batch_result)
        if not batch_result.get("passed"):
            raise VerifyError("Mininet command/capture checks failed")
        final["checks"].append("ping-and-capture")

        pcap_summary = validate_pcaps(output_dir)
        final["checks"].append("pcap-srv6-path")
        final["pcapSummary"] = pcap_summary
        final["passed"] = True
        write_json(output_dir / "result.json", final)

        log("SRv6 verification PASS")
        log("Artifacts: {}".format(output_dir))
        return 0
    except Exception as exc:
        final["error"] = str(exc)
        write_json(output_dir / "result.json", final)
        snapshot_rest(rest, output_dir, "failure")
        if mininet_proc and mininet_proc.poll() is None:
            if args.keep_env or result_file.exists():
                log("Leaving Mininet running for inspection.")
            else:
                terminate_process(mininet_proc, mininet_log_fp)
                mininet_log_fp = None
        log("SRv6 verification FAIL: {}".format(exc))
        log("Artifacts: {}".format(output_dir))
        return 1
    finally:
        if mininet_log_fp:
            mininet_log_fp.close()


if __name__ == "__main__":
    sys.exit(main())
