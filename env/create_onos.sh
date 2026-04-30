#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

APP_NAME="${APP_NAME:-org.onosproject.ngsdn-multirouter}"
ONOS_CONTAINER="${ONOS_CONTAINER:-onos}"
ONOS_IMAGE="${ONOS_IMAGE:-hub.rat.dev/onosproject/onos:2.7.0}"
ONOS_URL="${ONOS_URL:-http://127.0.0.1:8181}"
ONOS_INTERNAL_URL="${ONOS_INTERNAL_URL:-http://127.0.0.1:8181}"
ONOS_AUTH="${ONOS_AUTH:-onos:rocks}"
ONOS_REST_MODE="${ONOS_REST_MODE:-container}"
DOCKER_CMD="${DOCKER_CMD:-docker}"
ONOS_DOCKER_NETWORK="${ONOS_DOCKER_NETWORK:-onos-ngsdn}"
ONOS_DOCKER_SUBNET="${ONOS_DOCKER_SUBNET:-172.20.0.0/16}"
ONOS_DOCKER_GATEWAY="${ONOS_DOCKER_GATEWAY:-172.20.0.1}"
RECREATE_ONOS="${RECREATE_ONOS:-0}"
RECREATE_ON_PORT_CONFLICT="${RECREATE_ON_PORT_CONFLICT:-1}"

ROUTERS="${ROUTERS:-2}"
HOSTS_PER_ROUTER="${HOSTS_PER_ROUTER:-1}"
TOPOLOGY="${TOPOLOGY:-linear}"
GRPC_BASE="${GRPC_BASE:-9559}"
DEVICE_ID_BASE="${DEVICE_ID_BASE:-0}"

# ONOS runs in Docker by default, so it must use the host-side Docker bridge
# address to reach BMv2 simple_switch_grpc processes started by Mininet.
if [[ -z "${NETCFG_IP:-}" ]]; then
  if [[ "${ONOS_DOCKER_NETWORK}" == "bridge" ]]; then
    NETCFG_IP="172.17.0.1"
  else
    NETCFG_IP="${ONOS_DOCKER_GATEWAY}"
  fi
fi
NETCFG_FILE="${NETCFG_FILE:-target/env/netcfg-${TOPOLOGY}-${ROUTERS}r-${HOSTS_PER_ROUTER}h.json}"

BUILD_APP="${BUILD_APP:-1}"
BUILD_P4="${BUILD_P4:-1}"
ONOS_WAIT_RETRIES="${ONOS_WAIT_RETRIES:-60}"
ONOS_WAIT_SECONDS="${ONOS_WAIT_SECONDS:-2}"
ONOS_ACTION_RETRIES="${ONOS_ACTION_RETRIES:-20}"
ONOS_ACTION_WAIT_SECONDS="${ONOS_ACTION_WAIT_SECONDS:-3}"
ONOS_CURL_CONNECT_TIMEOUT="${ONOS_CURL_CONNECT_TIMEOUT:-2}"
ONOS_CURL_MAX_TIME="${ONOS_CURL_MAX_TIME:-5}"

read -r -a DOCKER <<< "${DOCKER_CMD}"
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
  echo "Removing existing ONOS container ${ONOS_CONTAINER}..."
  "${DOCKER[@]}" rm -f "${ONOS_CONTAINER}" >/dev/null
fi

ensure_onos_network

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
    -p 8181:8181 \
    -p 8101:8101 \
    -p 50051:50051 \
    -p 6640:6640 \
    -p 6653:6653 \
    -p 9876:9876 \
    "${ONOS_IMAGE}" >/dev/null
fi

if container_maps_grpc_base; then
  echo "ERROR: ${ONOS_CONTAINER} still maps host port ${GRPC_BASE}, which conflicts with BMv2 gRPC." >&2
  echo "       Rerun with RECREATE_ONOS=1 or choose another GRPC_BASE for both scripts." >&2
  exit 1
fi

REST_URL="$(onos_rest_url)"

echo "Waiting for ONOS REST API at ${REST_URL} using ${ONOS_REST_MODE} mode..."
ready=0
for _ in $(seq 1 "${ONOS_WAIT_RETRIES}"); do
  if onos_curl "${REST_URL}/onos/v1/applications" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep "${ONOS_WAIT_SECONDS}"
done

if [[ "${ready}" != "1" ]]; then
  echo "ONOS REST API is not ready: ${REST_URL} using ${ONOS_REST_MODE} mode" >&2
  echo "Container status:" >&2
  "${DOCKER[@]}" ps --filter "name=^/${ONOS_CONTAINER}$" --format '  {{.Names}} {{.Status}} {{.Ports}}' >&2 || true
  echo "Recent ONOS logs:" >&2
  "${DOCKER[@]}" logs --tail 40 "${ONOS_CONTAINER}" >&2 || true
  exit 1
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
  org.onosproject.drivers.bmv2
  org.onosproject.protocols.grpc
  org.onosproject.protocols.p4runtime
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

echo "Generating netcfg ${NETCFG_FILE}..."
mkdir -p "$(dirname "${NETCFG_FILE}")"
./tools/build_netcfg.py \
  --ip "${NETCFG_IP}" \
  --routers "${ROUTERS}" \
  --hosts-per-router "${HOSTS_PER_ROUTER}" \
  --topology "${TOPOLOGY}" \
  --grpc-base "${GRPC_BASE}" \
  --device-id-base "${DEVICE_ID_BASE}" \
  --output "${NETCFG_FILE}"

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
echo "  REST mode:      ${ONOS_REST_MODE}"
echo "  Docker network: ${ONOS_DOCKER_NETWORK}"
echo "  netcfg IP:      ${NETCFG_IP}"
echo "  topology:       ${TOPOLOGY}, routers=${ROUTERS}, hosts/router=${HOSTS_PER_ROUTER}"
echo
echo "Start Mininet with matching parameters:"
echo "  ROUTERS=${ROUTERS} HOSTS_PER_ROUTER=${HOSTS_PER_ROUTER} TOPOLOGY=${TOPOLOGY} ./env/create_mininet.sh"
