#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

GRPC_EXE="${GRPC_EXE:-/usr/bin/simple_switch_grpc}"
JSON_PATH="${JSON_PATH:-${ROOT_DIR}/p4build/ngsdn_tutorial/ngsdn_tutorial.json}"
P4INFO_PATH="${P4INFO_PATH:-${ROOT_DIR}/p4build/ngsdn_tutorial.p4info.pb.txt}"

ROUTERS="${ROUTERS:-2}"
HOSTS_PER_ROUTER="${HOSTS_PER_ROUTER:-1}"
TOPOLOGY="${TOPOLOGY:-linear}"
THRIFT_PORT_BASE="${THRIFT_PORT_BASE:-9090}"
GRPC_BASE="${GRPC_BASE:-9559}"
DEVICE_ID_BASE="${DEVICE_ID_BASE:-0}"
CPU_PORT="${CPU_PORT:-255}"
ONOS_IP="${ONOS_IP:-127.0.0.1}"
PCAP_DUMP="${PCAP_DUMP:-0}"
CLEAN_MININET="${CLEAN_MININET:-1}"
CLEAN_STALE_P4="${CLEAN_STALE_P4:-1}"
BUILD_P4_IF_MISSING="${BUILD_P4_IF_MISSING:-1}"
SUDO_CMD="${SUDO_CMD:-sudo}"

read -r -a SUDO <<< "${SUDO_CMD}"
if [[ "$(id -u)" == "0" ]]; then
  SUDO=()
fi

if [[ ! -x "${GRPC_EXE}" ]]; then
  echo "simple_switch_grpc not found or not executable: ${GRPC_EXE}" >&2
  exit 1
fi

if [[ ! -f "${JSON_PATH}" || ! -f "${P4INFO_PATH}" ]]; then
  if [[ "${BUILD_P4_IF_MISSING}" == "1" ]]; then
    echo "P4 artifacts are missing; building them first..."
    ./tools/build_p4.sh
  fi

  if [[ ! -f "${JSON_PATH}" || ! -f "${P4INFO_PATH}" ]]; then
    echo "Missing P4 artifacts:" >&2
    echo "  ${JSON_PATH}" >&2
    echo "  ${P4INFO_PATH}" >&2
    exit 1
  fi
fi

if [[ "${CLEAN_MININET}" == "1" ]]; then
  if [[ "${CLEAN_STALE_P4}" == "1" ]]; then
    echo "Stopping stale P4Runtime/Mininet processes..."
    "${SUDO[@]}" pkill -f "multi_router_p4runtime.py" >/dev/null 2>&1 || true
    "${SUDO[@]}" pkill -f "simple_switch_grpc" >/dev/null 2>&1 || true
  fi

  echo "Cleaning previous Mininet state..."
  "${SUDO[@]}" mn -c
fi

args=(
  python3 ./mininet/multi_router_p4runtime.py
  --grpc-exe "${GRPC_EXE}"
  --json "${JSON_PATH}"
  --p4info "${P4INFO_PATH}"
  --num-routers "${ROUTERS}"
  --num-hosts-per-router "${HOSTS_PER_ROUTER}"
  --topology "${TOPOLOGY}"
  --thrift-port-base "${THRIFT_PORT_BASE}"
  --grpc-port-base "${GRPC_BASE}"
  --device-id-base "${DEVICE_ID_BASE}"
  --cpu-port "${CPU_PORT}"
  --onos-ip "${ONOS_IP}"
)

if [[ "${PCAP_DUMP}" == "1" ]]; then
  args+=(--pcap-dump)
fi

echo
echo "Starting Mininet environment."
echo "  topology:       ${TOPOLOGY}, routers=${ROUTERS}, hosts/router=${HOSTS_PER_ROUTER}"
echo "  BMv2 JSON:      ${JSON_PATH}"
echo "  P4Info:         ${P4INFO_PATH}"
echo "  gRPC base port: ${GRPC_BASE}"
echo

exec "${SUDO[@]}" "${args[@]}"
