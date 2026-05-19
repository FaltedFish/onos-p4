#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

GRPC_EXE="${GRPC_EXE:-/usr/bin/simple_switch_grpc}"
JSON_PATH="${JSON_PATH:-${ROOT_DIR}/p4build/ngsdn_tutorial/ngsdn_tutorial.json}"
P4INFO_PATH="${P4INFO_PATH:-${ROOT_DIR}/p4build/ngsdn_tutorial.p4info.pb.txt}"

THRIFT_PORT_BASE_EXPLICIT="${THRIFT_PORT_BASE+x}"
GRPC_BASE_EXPLICIT="${GRPC_BASE+x}"
DEVICE_ID_BASE_EXPLICIT="${DEVICE_ID_BASE+x}"
CPU_PORT_EXPLICIT="${CPU_PORT+x}"

ROUTERS="${ROUTERS:-2}"
HOSTS_PER_ROUTER="${HOSTS_PER_ROUTER:-1}"
TOPOLOGY="${TOPOLOGY:-linear}"
TOPOLOGY_CONFIG="${TOPOLOGY_CONFIG:-}"
TOPOLOGY_FILE="${TOPOLOGY_FILE:-}"
TOPOLOGY_FILE_EXPLICIT=0
LINK_BW="${LINK_BW:-}"
LINK_DELAY="${LINK_DELAY:-}"
THRIFT_PORT_BASE="${THRIFT_PORT_BASE:-9090}"
GRPC_BASE="${GRPC_BASE:-9559}"
DEVICE_ID_BASE="${DEVICE_ID_BASE:-0}"
CPU_PORT="${CPU_PORT:-255}"
ONOS_IP="${ONOS_IP:-127.0.0.1}"
PCAP_DUMP="${PCAP_DUMP:-0}"
CLEAN_MININET="${CLEAN_MININET:-1}"
CLEAN_STALE_P4="${CLEAN_STALE_P4:-1}"
BUILD_P4_IF_MISSING="${BUILD_P4_IF_MISSING:-1}"
MININET_BATCH_JSON="${MININET_BATCH_JSON:-}"
MININET_BATCH_RESULT="${MININET_BATCH_RESULT:-}"
MININET_NO_CLI="${MININET_NO_CLI:-0}"
MININET_KEEP_RUNNING_AFTER_BATCH="${MININET_KEEP_RUNNING_AFTER_BATCH:-0}"
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

if [[ -n "${TOPOLOGY_FILE}" ]]; then
  TOPOLOGY_FILE_EXPLICIT=1
else
  if [[ -n "${TOPOLOGY_CONFIG}" ]]; then
    TOPOLOGY_NAME="$(basename "${TOPOLOGY_CONFIG}")"
    TOPOLOGY_NAME="${TOPOLOGY_NAME%.*}"
    TOPOLOGY_FILE="target/env/topology-${TOPOLOGY_NAME}.json"
  else
    TOPOLOGY_FILE="target/env/topology-${TOPOLOGY}-${ROUTERS}r-${HOSTS_PER_ROUTER}h.json"
  fi
fi

if [[ "${TOPOLOGY_FILE_EXPLICIT}" == "1" ]]; then
  if [[ ! -f "${TOPOLOGY_FILE}" ]]; then
    echo "Topology file not found: ${TOPOLOGY_FILE}" >&2
    exit 1
  fi
else
  echo "Preparing topology ${TOPOLOGY_FILE}..."
  mkdir -p "$(dirname "${TOPOLOGY_FILE}")"
  topology_args=(
    ./tools/build_topology.py
    --output "${TOPOLOGY_FILE}"
  )
  if [[ -n "${TOPOLOGY_CONFIG}" ]]; then
    topology_args+=(--config "${TOPOLOGY_CONFIG}")
    if [[ -n "${GRPC_BASE_EXPLICIT}" ]]; then
      topology_args+=(--grpc-base "${GRPC_BASE}")
    fi
    if [[ -n "${THRIFT_PORT_BASE_EXPLICIT}" ]]; then
      topology_args+=(--thrift-base "${THRIFT_PORT_BASE}")
    fi
    if [[ -n "${DEVICE_ID_BASE_EXPLICIT}" ]]; then
      topology_args+=(--device-id-base "${DEVICE_ID_BASE}")
    fi
    if [[ -n "${CPU_PORT_EXPLICIT}" ]]; then
      topology_args+=(--cpu-port "${CPU_PORT}")
    fi
  else
    topology_args+=(
      --grpc-base "${GRPC_BASE}"
      --thrift-base "${THRIFT_PORT_BASE}"
      --device-id-base "${DEVICE_ID_BASE}"
      --cpu-port "${CPU_PORT}"
      --routers "${ROUTERS}"
      --hosts-per-router "${HOSTS_PER_ROUTER}"
      --topology "${TOPOLOGY}"
    )
  fi
  "${topology_args[@]}"
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
  --topology-file "${TOPOLOGY_FILE}"
  --cpu-port "${CPU_PORT}"
  --onos-ip "${ONOS_IP}"
)

if [[ -n "${LINK_BW}" ]]; then
  args+=(--link-bw "${LINK_BW}")
fi

if [[ -n "${LINK_DELAY}" ]]; then
  args+=(--link-delay "${LINK_DELAY}")
fi

if [[ "${PCAP_DUMP}" == "1" ]]; then
  args+=(--pcap-dump)
fi
if [[ -n "${MININET_BATCH_JSON}" ]]; then
  args+=(--batch-json "${MININET_BATCH_JSON}")
fi
if [[ -n "${MININET_BATCH_RESULT}" ]]; then
  args+=(--batch-result "${MININET_BATCH_RESULT}")
fi
if [[ "${MININET_NO_CLI}" == "1" ]]; then
  args+=(--no-cli)
fi
if [[ "${MININET_KEEP_RUNNING_AFTER_BATCH}" == "1" ]]; then
  args+=(--keep-running-after-batch)
fi

echo
echo "Starting Mininet environment."
echo "  topology file:  ${TOPOLOGY_FILE}"
if [[ "${TOPOLOGY_FILE_EXPLICIT}" == "1" ]]; then
  :
elif [[ -n "${TOPOLOGY_CONFIG}" ]]; then
  echo "  config file:    ${TOPOLOGY_CONFIG}"
else
  echo "  template:       ${TOPOLOGY}, routers=${ROUTERS}, hosts/router=${HOSTS_PER_ROUTER}"
fi
echo "  BMv2 JSON:      ${JSON_PATH}"
echo "  P4Info:         ${P4INFO_PATH}"
echo "  gRPC base port: ${GRPC_BASE}"
if [[ -n "${LINK_BW}" || -n "${LINK_DELAY}" ]]; then
  echo "  link bw:        ${LINK_BW:-default}"
  echo "  link delay:     ${LINK_DELAY:-default}"
fi
if [[ -n "${MININET_BATCH_JSON}" ]]; then
  echo "  batch json:     ${MININET_BATCH_JSON}"
  echo "  batch result:   ${MININET_BATCH_RESULT}"
  echo "  keep running:   ${MININET_KEEP_RUNNING_AFTER_BATCH}"
fi
echo

exec "${SUDO[@]}" "${args[@]}"
