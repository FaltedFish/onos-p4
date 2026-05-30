#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

APP_NAME="${APP_NAME:-org.onosproject.ngsdn-multirouter}"
ONOS_CONTAINER="${ONOS_CONTAINER:-onos}"
ONOS_IMAGE="${ONOS_IMAGE:-hub.rat.dev/onosproject/onos:2.7.0}"
ONOS_URL="${ONOS_URL:-http://127.0.0.1:${ONOS_REST_PORT:-8181}}"
if [[ -z "${ONOS_REST_PORT:-}" ]]; then
  if [[ "${ONOS_URL}" =~ ^https?://[^/]*:([0-9]+)(/.*)?$ ]]; then
    ONOS_REST_PORT="${BASH_REMATCH[1]}"
  else
    ONOS_REST_PORT="8181"
  fi
fi
ONOS_PORT_OFFSET="$((ONOS_REST_PORT - 8181))"
ONOS_INTERNAL_URL="${ONOS_INTERNAL_URL:-http://127.0.0.1:8181}"
ONOS_AUTH="${ONOS_AUTH:-onos:rocks}"
ONOS_REST_MODE="${ONOS_REST_MODE:-container}"
ONOS_SSH_PORT="${ONOS_SSH_PORT:-$((8101 + ONOS_PORT_OFFSET))}"
ONOS_GRPC_PORT="${ONOS_GRPC_PORT:-$((50051 + ONOS_PORT_OFFSET))}"
ONOS_OFCONFIG_PORT="${ONOS_OFCONFIG_PORT:-$((6640 + ONOS_PORT_OFFSET))}"
ONOS_OPENFLOW_PORT="${ONOS_OPENFLOW_PORT:-$((6653 + ONOS_PORT_OFFSET))}"
ONOS_TEST_PORT="${ONOS_TEST_PORT:-$((9876 + ONOS_PORT_OFFSET))}"
DOCKER_CMD="${DOCKER_CMD:-docker}"
ONOS_DOCKER_NETWORK="${ONOS_DOCKER_NETWORK:-onos-ngsdn}"
ONOS_DOCKER_SUBNET="${ONOS_DOCKER_SUBNET:-172.20.0.0/16}"
ONOS_DOCKER_GATEWAY="${ONOS_DOCKER_GATEWAY:-172.20.0.1}"
RECREATE_ONOS="${RECREATE_ONOS:-0}"
RECREATE_ON_PORT_CONFLICT="${RECREATE_ON_PORT_CONFLICT:-1}"
RECREATE_ONOS_NETWORK="${RECREATE_ONOS_NETWORK:-0}"
CLEAN_STALE_ONOS_NETWORK="${CLEAN_STALE_ONOS_NETWORK:-1}"
RECREATE_UNHEALTHY_ONOS="${RECREATE_UNHEALTHY_ONOS:-1}"

THRIFT_PORT_BASE_EXPLICIT="${THRIFT_PORT_BASE+x}"
GRPC_BASE_EXPLICIT="${GRPC_BASE+x}"
DEVICE_ID_BASE_EXPLICIT="${DEVICE_ID_BASE+x}"
CPU_PORT_EXPLICIT="${CPU_PORT+x}"

ROUTERS="${ROUTERS:-2}"
HOSTS_PER_ROUTER="${HOSTS_PER_ROUTER:-1}"
TOPOLOGY="${TOPOLOGY:-linear}"
DOMAIN="${DOMAIN:-}"
TOPOLOGY_CONFIG="${TOPOLOGY_CONFIG:-}"
TOPOLOGY_FILE="${TOPOLOGY_FILE:-}"
TOPOLOGY_FILE_EXPLICIT=0
LINK_BW="${LINK_BW:-}"
LINK_DELAY="${LINK_DELAY:-}"
GRPC_BASE="${GRPC_BASE:-9559}"
THRIFT_PORT_BASE="${THRIFT_PORT_BASE:-9090}"
DEVICE_ID_BASE="${DEVICE_ID_BASE:-0}"
CPU_PORT="${CPU_PORT:-255}"

# ONOS runs in Docker by default, so it must use the host-side Docker bridge
# address to reach BMv2 simple_switch_grpc processes started by Mininet.
if [[ -z "${NETCFG_IP:-}" ]]; then
  if [[ "${ONOS_DOCKER_NETWORK}" == "bridge" ]]; then
    NETCFG_IP="172.17.0.1"
  else
    NETCFG_IP="${ONOS_DOCKER_GATEWAY}"
  fi
fi
if [[ -n "${TOPOLOGY_FILE}" ]]; then
  TOPOLOGY_FILE_EXPLICIT=1
  TOPOLOGY_NAME="$(basename "${TOPOLOGY_FILE}")"
  TOPOLOGY_NAME="${TOPOLOGY_NAME%.*}"
  TOPOLOGY_NAME="${TOPOLOGY_NAME#topology-}"
else
  if [[ -n "${TOPOLOGY_CONFIG}" ]]; then
    TOPOLOGY_NAME="$(basename "${TOPOLOGY_CONFIG}")"
    TOPOLOGY_NAME="${TOPOLOGY_NAME%.*}"
    TOPOLOGY_FILE="target/env/topology-${TOPOLOGY_NAME}.json"
  else
    TOPOLOGY_FILE="target/env/topology-${TOPOLOGY}-${ROUTERS}r-${HOSTS_PER_ROUTER}h.json"
  fi
fi
if [[ -n "${NETCFG_FILE:-}" ]]; then
  NETCFG_FILE="${NETCFG_FILE}"
elif [[ -n "${TOPOLOGY_CONFIG}" || "${TOPOLOGY_FILE_EXPLICIT}" == "1" ]]; then
  if [[ -n "${DOMAIN}" ]]; then
    NETCFG_FILE="target/env/netcfg-${TOPOLOGY_NAME}-${DOMAIN}.json"
  else
    NETCFG_FILE="target/env/netcfg-${TOPOLOGY_NAME}.json"
  fi
else
  if [[ -n "${DOMAIN}" ]]; then
    NETCFG_FILE="target/env/netcfg-${TOPOLOGY}-${ROUTERS}r-${HOSTS_PER_ROUTER}h-${DOMAIN}.json"
  else
    NETCFG_FILE="target/env/netcfg-${TOPOLOGY}-${ROUTERS}r-${HOSTS_PER_ROUTER}h.json"
  fi
fi

BUILD_APP="${BUILD_APP:-1}"
BUILD_P4="${BUILD_P4:-1}"
ONOS_WAIT_RETRIES="${ONOS_WAIT_RETRIES:-60}"
ONOS_WAIT_SECONDS="${ONOS_WAIT_SECONDS:-2}"
ONOS_ACTION_RETRIES="${ONOS_ACTION_RETRIES:-40}"
ONOS_ACTION_WAIT_SECONDS="${ONOS_ACTION_WAIT_SECONDS:-3}"
ONOS_CURL_CONNECT_TIMEOUT="${ONOS_CURL_CONNECT_TIMEOUT:-2}"
ONOS_CURL_MAX_TIME="${ONOS_CURL_MAX_TIME:-5}"

read -r -a DOCKER <<< "${DOCKER_CMD}"
ONOS_CONTAINER_RECREATED=0
CURL_ARGS=(
  --fail
  -sS
  --connect-timeout "${ONOS_CURL_CONNECT_TIMEOUT}"
  --max-time "${ONOS_CURL_MAX_TIME}"
  --user "${ONOS_AUTH}"
)
CURL_STATUS_ARGS=(
  -sS
  --connect-timeout "${ONOS_CURL_CONNECT_TIMEOUT}"
  --max-time "${ONOS_CURL_MAX_TIME}"
  --user "${ONOS_AUTH}"
)

docker_exists() {
  "${DOCKER[@]}" inspect "${ONOS_CONTAINER}" >/dev/null 2>&1
}

docker_running() {
  [[ "$("${DOCKER[@]}" inspect -f '{{.State.Running}}' "${ONOS_CONTAINER}" 2>/dev/null || true)" == "true" ]]
}

container_maps_grpc_base() {
  "${DOCKER[@]}" port "${ONOS_CONTAINER}" 2>/dev/null |
    grep -Eq "(0\.0\.0\.0|\[::\]|127\.0\.0\.1):${GRPC_BASE}$"
}

docker_network_exists() {
  "${DOCKER[@]}" network inspect "${ONOS_DOCKER_NETWORK}" >/dev/null 2>&1
}

container_uses_onos_network() {
  [[ "$("${DOCKER[@]}" inspect -f "{{with index .NetworkSettings.Networks \"${ONOS_DOCKER_NETWORK}\"}}true{{end}}" "${ONOS_CONTAINER}" 2>/dev/null || true)" == "true" ]]
}

onos_network_ipam() {
  "${DOCKER[@]}" network inspect -f '{{range .IPAM.Config}}{{.Subnet}} {{.Gateway}}{{end}}' \
    "${ONOS_DOCKER_NETWORK}" 2>/dev/null || true
}

onos_network_bridge() {
  local bridge
  local network_id
  bridge="$("${DOCKER[@]}" network inspect -f '{{index .Options "com.docker.network.bridge.name"}}' \
    "${ONOS_DOCKER_NETWORK}" 2>/dev/null || true)"
  if [[ -n "${bridge}" && "${bridge}" != "<no value>" ]]; then
    printf '%s' "${bridge}"
    return 0
  fi

  network_id="$("${DOCKER[@]}" network inspect -f '{{.Id}}' "${ONOS_DOCKER_NETWORK}" 2>/dev/null || true)"
  if [[ -n "${network_id}" ]]; then
    printf 'br-%.12s' "${network_id}"
  fi
}

onos_network_bridge_has_gateway() {
  local bridge="$1"
  ip -4 addr show dev "${bridge}" 2>/dev/null |
    grep -Eq "inet[[:space:]]+${ONOS_DOCKER_GATEWAY}/"
}

remove_onos_container() {
  if docker_exists; then
    echo "Removing existing ONOS container ${ONOS_CONTAINER}..."
    "${DOCKER[@]}" rm -f "${ONOS_CONTAINER}" >/dev/null
    ONOS_CONTAINER_RECREATED=1
  fi
}

remove_onos_network() {
  if [[ "${ONOS_DOCKER_NETWORK}" == "bridge" ]]; then
    return 0
  fi
  if docker_network_exists; then
    echo "Removing Docker network ${ONOS_DOCKER_NETWORK}..."
    "${DOCKER[@]}" network rm "${ONOS_DOCKER_NETWORK}" >/dev/null
  fi
}

network_needs_recreate() {
  local bridge
  local ipam

  if [[ "${ONOS_DOCKER_NETWORK}" == "bridge" ]]; then
    return 1
  fi
  if ! docker_network_exists; then
    return 1
  fi

  ipam="$(onos_network_ipam)"
  if [[ "${ipam}" != "${ONOS_DOCKER_SUBNET} ${ONOS_DOCKER_GATEWAY}" ]]; then
    echo "Existing Docker network ${ONOS_DOCKER_NETWORK} uses '${ipam}', expected '${ONOS_DOCKER_SUBNET} ${ONOS_DOCKER_GATEWAY}'."
    return 0
  fi

  bridge="$(onos_network_bridge)"
  if [[ -z "${bridge}" || ! -e "/sys/class/net/${bridge}" ]]; then
    echo "Existing Docker network ${ONOS_DOCKER_NETWORK} has no host bridge interface."
    return 0
  fi
  if ! onos_network_bridge_has_gateway "${bridge}"; then
    echo "Existing Docker network ${ONOS_DOCKER_NETWORK} bridge ${bridge} is missing gateway ${ONOS_DOCKER_GATEWAY}."
    return 0
  fi

  return 1
}

recreate_onos_network() {
  remove_onos_container
  remove_onos_network
}

ensure_onos_network() {
  if [[ "${ONOS_DOCKER_NETWORK}" == "bridge" ]]; then
    return 0
  fi
  if docker_network_exists; then
    return 0
  fi
  echo "Creating Docker network ${ONOS_DOCKER_NETWORK} (${ONOS_DOCKER_SUBNET}, gateway ${ONOS_DOCKER_GATEWAY})..."
  "${DOCKER[@]}" network create \
    --driver bridge \
    --subnet "${ONOS_DOCKER_SUBNET}" \
    --gateway "${ONOS_DOCKER_GATEWAY}" \
    "${ONOS_DOCKER_NETWORK}" >/dev/null
}

onos_rest_url() {
  if [[ "${ONOS_REST_MODE}" == "container" ]]; then
    printf '%s' "${ONOS_INTERNAL_URL}"
  else
    printf '%s' "${ONOS_URL}"
  fi
}

onos_curl() {
  if [[ "${ONOS_REST_MODE}" == "container" ]]; then
    "${DOCKER[@]}" exec "${ONOS_CONTAINER}" curl "${CURL_ARGS[@]}" "$@"
  else
    curl "${CURL_ARGS[@]}" "$@"
  fi
}

onos_curl_with_status() {
  if [[ "${ONOS_REST_MODE}" == "container" ]]; then
    "${DOCKER[@]}" exec "${ONOS_CONTAINER}" curl "${CURL_STATUS_ARGS[@]}" \
      -w $'\n%{http_code}' "$@"
  else
    curl "${CURL_STATUS_ARGS[@]}" -w $'\n%{http_code}' "$@"
  fi
}

onos_curl_retry() {
  local attempt
  local output
  local status
  local body
  local rc
  for attempt in $(seq 1 "${ONOS_ACTION_RETRIES}"); do
    output="$(onos_curl_with_status "$@" 2>&1)"
    rc=$?
    status="${output##*$'\n'}"
    body="${output%$'\n'*}"
    if [[ "${rc}" == "0" && "${status}" =~ ^2[0-9][0-9]$ && "${status}" != "207" ]]; then
      printf '%s' "${body}"
      return 0
    fi
    if [[ -n "${body}" && "${body}" != "${status}" ]]; then
      printf '%s\n' "${body}" >&2
    fi
    echo "ONOS REST action failed with HTTP ${status}, retry ${attempt}/${ONOS_ACTION_RETRIES}..." >&2
    sleep "${ONOS_ACTION_WAIT_SECONDS}"
  done
  return 1
}

onos_copy_to_container() {
  local src="$1"
  local dst="$2"
  "${DOCKER[@]}" cp "${src}" "${ONOS_CONTAINER}:${dst}"
}

onos_print_diagnostics() {
  echo "Container status:" >&2
  "${DOCKER[@]}" ps --filter "name=^/${ONOS_CONTAINER}$" --format '  {{.Names}} {{.Status}} {{.Ports}}' >&2 || true
  echo "Recent ONOS logs:" >&2
  "${DOCKER[@]}" logs --tail 80 "${ONOS_CONTAINER}" >&2 || true
}

start_onos_container() {
  if docker_exists; then
    if docker_running; then
      echo "ONOS container ${ONOS_CONTAINER} is already running."
    else
      echo "Starting existing ONOS container ${ONOS_CONTAINER}..."
      "${DOCKER[@]}" start "${ONOS_CONTAINER}" >/dev/null
    fi
  else
    echo "Creating ONOS container ${ONOS_CONTAINER} from ${ONOS_IMAGE}..."
    "${DOCKER[@]}" run -d \
      --name "${ONOS_CONTAINER}" \
      --network "${ONOS_DOCKER_NETWORK}" \
      -p "${ONOS_REST_PORT}:8181" \
      -p "${ONOS_SSH_PORT}:8101" \
      -p "${ONOS_GRPC_PORT}:50051" \
      -p "${ONOS_OFCONFIG_PORT}:6640" \
      -p "${ONOS_OPENFLOW_PORT}:6653" \
      -p "${ONOS_TEST_PORT}:9876" \
      "${ONOS_IMAGE}" >/dev/null
    ONOS_CONTAINER_RECREATED=1
  fi
}

onos_app_service_ready() {
  local output
  local status
  local body
  local rc

  output="$(onos_curl_with_status "${REST_URL}/onos/v1/applications" 2>/dev/null || true)"
  rc=$?
  status="${output##*$'\n'}"
  body="${output%$'\n'*}"

  [[ "${rc}" == "0" && "${status}" == "200" && "${body}" == *'"applications"'* ]]
}

wait_for_onos_ready() {
  local attempt

  echo "Waiting for ONOS application service at ${REST_URL} using ${ONOS_REST_MODE} mode..."
  for attempt in $(seq 1 "${ONOS_WAIT_RETRIES}"); do
    if onos_app_service_ready; then
      return 0
    fi
    sleep "${ONOS_WAIT_SECONDS}"
  done
  return 1
}

if [[ "${RECREATE_ONOS_NETWORK}" == "1" ]]; then
  echo "RECREATE_ONOS_NETWORK=1, recreating Docker network ${ONOS_DOCKER_NETWORK}."
  recreate_onos_network
elif [[ "${CLEAN_STALE_ONOS_NETWORK}" == "1" ]] && network_needs_recreate; then
  echo "Recreating stale Docker network ${ONOS_DOCKER_NETWORK}."
  recreate_onos_network
fi

if docker_exists &&
    [[ "${RECREATE_ONOS}" != "1" ]] &&
    [[ "${RECREATE_ON_PORT_CONFLICT}" == "1" ]] &&
    container_maps_grpc_base; then
  echo "Existing ONOS container ${ONOS_CONTAINER} maps host port ${GRPC_BASE}."
  echo "That port is reserved for BMv2 r1 gRPC, so the ONOS container must be recreated."
  RECREATE_ONOS=1
fi

if docker_exists &&
    [[ "${RECREATE_ONOS}" != "1" ]] &&
    ! container_uses_onos_network; then
  echo "Existing ONOS container ${ONOS_CONTAINER} is not attached to Docker network ${ONOS_DOCKER_NETWORK}."
  echo "Recreating it so published ports and netcfg use the expected bridge."
  RECREATE_ONOS=1
fi

if [[ "${RECREATE_ONOS}" == "1" ]] && docker_exists; then
  remove_onos_container
fi

ensure_onos_network

start_onos_container

if container_maps_grpc_base; then
  echo "ERROR: ${ONOS_CONTAINER} still maps host port ${GRPC_BASE}, which conflicts with BMv2 gRPC." >&2
  echo "       Rerun with RECREATE_ONOS=1 or choose another GRPC_BASE for both scripts." >&2
  exit 1
fi

REST_URL="$(onos_rest_url)"

if ! wait_for_onos_ready; then
  if [[ "${RECREATE_UNHEALTHY_ONOS}" == "1" && "${ONOS_CONTAINER_RECREATED}" != "1" ]]; then
    echo "ONOS application service did not become ready; recreating ${ONOS_CONTAINER} once..."
    onos_print_diagnostics
    remove_onos_container
    start_onos_container
    if container_maps_grpc_base; then
      echo "ERROR: ${ONOS_CONTAINER} maps host port ${GRPC_BASE}, which conflicts with BMv2 gRPC." >&2
      echo "       Rerun with RECREATE_ONOS=1 or choose another GRPC_BASE for both scripts." >&2
      exit 1
    fi
    if ! wait_for_onos_ready; then
      echo "ONOS application service is not ready after recreating ${ONOS_CONTAINER}: ${REST_URL}" >&2
      onos_print_diagnostics
      exit 1
    fi
  else
    echo "ONOS application service is not ready: ${REST_URL} using ${ONOS_REST_MODE} mode" >&2
    onos_print_diagnostics
    exit 1
  fi
fi

if [[ "${BUILD_APP}" == "1" ]]; then
  echo "Building ONOS app (BUILD_P4=${BUILD_P4})..."
  BUILD_P4="${BUILD_P4}" ./build.sh
fi

OAR_FILE="target/ngsdn-multirouter-1.0-SNAPSHOT.oar"
if [[ ! -f "${OAR_FILE}" ]]; then
  echo "Missing OAR file: ${OAR_FILE}" >&2
  echo "Run ./build.sh first or set BUILD_APP=1." >&2
  exit 1
fi

echo "Activating ONOS base apps..."
base_apps=(
  org.onosproject.drivers
  org.onosproject.protocols.grpc
  org.onosproject.protocols.p4runtime
  org.onosproject.p4runtime
  org.onosproject.drivers.p4runtime
  org.onosproject.pipelines.basic
  org.onosproject.drivers.stratum
  org.onosproject.drivers.bmv2
  org.onosproject.lldpprovider
  org.onosproject.netcfglinksprovider
  org.onosproject.netcfghostprovider
)

for app in "${base_apps[@]}"; do
  onos_curl_retry -X POST \
    "${REST_URL}/onos/v1/applications/${app}/active" >/dev/null
done

echo "Installing ${APP_NAME}..."
if [[ "${ONOS_REST_MODE}" == "container" ]]; then
  "${DOCKER[@]}" exec "${ONOS_CONTAINER}" curl -sS \
    --connect-timeout "${ONOS_CURL_CONNECT_TIMEOUT}" \
    --max-time "${ONOS_CURL_MAX_TIME}" \
    --user "${ONOS_AUTH}" -X DELETE \
    "${REST_URL}/onos/v1/applications/${APP_NAME}" >/dev/null 2>&1 || true
else
  curl -sS --connect-timeout "${ONOS_CURL_CONNECT_TIMEOUT}" --max-time "${ONOS_CURL_MAX_TIME}" \
    --user "${ONOS_AUTH}" -X DELETE \
    "${REST_URL}/onos/v1/applications/${APP_NAME}" >/dev/null 2>&1 || true
fi
sleep 2
if [[ "${ONOS_REST_MODE}" == "container" ]]; then
  OAR_CONTAINER_FILE="/tmp/$(basename "${OAR_FILE}")"
  onos_copy_to_container "${OAR_FILE}" "${OAR_CONTAINER_FILE}"
  onos_curl_retry -X POST -H 'Content-Type:application/octet-stream' \
    "${REST_URL}/onos/v1/applications?activate=true" \
    --data-binary @"${OAR_CONTAINER_FILE}" >/dev/null
else
  onos_curl_retry -X POST -H 'Content-Type:application/octet-stream' \
    "${REST_URL}/onos/v1/applications?activate=true" \
    --data-binary @"${OAR_FILE}" >/dev/null
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

echo "Generating netcfg ${NETCFG_FILE}..."
mkdir -p "$(dirname "${NETCFG_FILE}")"
netcfg_args=(
  ./tools/build_netcfg.py
  --ip "${NETCFG_IP}"
  --topology-file "${TOPOLOGY_FILE}"
  --output "${NETCFG_FILE}"
)
if [[ -n "${DOMAIN}" ]]; then
  netcfg_args+=(--domain "${DOMAIN}")
fi
"${netcfg_args[@]}"

echo "Pushing netcfg to ONOS..."
if [[ "${ONOS_REST_MODE}" == "container" ]]; then
  NETCFG_CONTAINER_FILE="/tmp/$(basename "${NETCFG_FILE}")"
  onos_copy_to_container "${NETCFG_FILE}" "${NETCFG_CONTAINER_FILE}"
  onos_curl_retry -X POST -H 'Content-Type:application/json' \
    "${REST_URL}/onos/v1/network/configuration" \
    -d @"${NETCFG_CONTAINER_FILE}" >/dev/null
else
  onos_curl_retry -X POST -H 'Content-Type:application/json' \
    "${REST_URL}/onos/v1/network/configuration" \
    -d @"${NETCFG_FILE}" >/dev/null
fi

echo
echo "ONOS environment is ready."
echo "  ONOS URL:       ${ONOS_URL}"
echo "  ONOS container: ${ONOS_CONTAINER}"
if [[ -n "${DOMAIN}" ]]; then
  echo "  domain:         ${DOMAIN}"
fi
echo "  REST mode:      ${ONOS_REST_MODE}"
echo "  Docker network: ${ONOS_DOCKER_NETWORK}"
echo "  netcfg IP:      ${NETCFG_IP}"
echo "  topology file:  ${TOPOLOGY_FILE}"
if [[ "${TOPOLOGY_FILE_EXPLICIT}" == "1" ]]; then
  :
elif [[ -n "${TOPOLOGY_CONFIG}" ]]; then
  echo "  config file:    ${TOPOLOGY_CONFIG}"
else
  echo "  template:       ${TOPOLOGY}, routers=${ROUTERS}, hosts/router=${HOSTS_PER_ROUTER}"
fi
echo
echo "Start Mininet with matching parameters:"
MININET_ENV=("TOPOLOGY_FILE=${TOPOLOGY_FILE}")
if [[ -n "${LINK_BW}" ]]; then
  MININET_ENV+=("LINK_BW=${LINK_BW}")
fi
if [[ -n "${LINK_DELAY}" ]]; then
  MININET_ENV+=("LINK_DELAY=${LINK_DELAY}")
fi
echo "  ${MININET_ENV[*]} ./env/create_mininet.sh"
