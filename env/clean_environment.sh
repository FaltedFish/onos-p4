#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

DOCKER_CMD="${DOCKER_CMD:-docker}"
SUDO_CMD="${SUDO_CMD:-sudo}"
ONOS_CONTAINER="${ONOS_CONTAINER:-onos}"
ONOS_CONTAINERS="${ONOS_CONTAINERS:-}"
ONOS_DOCKER_NETWORK="${ONOS_DOCKER_NETWORK:-onos-ngsdn}"

CLEAN_MININET="${CLEAN_MININET:-1}"
CLEAN_STALE_P4="${CLEAN_STALE_P4:-1}"
CLEAN_ONOS_DOCKER="${CLEAN_ONOS_DOCKER:-1}"
CLEAN_ONOS_NETWORK="${CLEAN_ONOS_NETWORK:-1}"
REMOVE_ONOS_VOLUMES="${REMOVE_ONOS_VOLUMES:-0}"
DRY_RUN="${DRY_RUN:-0}"

read -r -a DOCKER <<< "${DOCKER_CMD}"
read -r -a SUDO <<< "${SUDO_CMD}"
if [[ "$(id -u)" == "0" ]]; then
  SUDO=()
fi

run() {
  printf '+'
  printf ' %q' "$@"
  printf '\n'
  if [[ "${DRY_RUN}" != "1" ]]; then
    "$@"
  fi
}

run_allow_fail() {
  printf '+'
  printf ' %q' "$@"
  printf ' || true\n'
  if [[ "${DRY_RUN}" != "1" ]]; then
    "$@" >/dev/null 2>&1 || true
  fi
}

docker_available() {
  command -v "${DOCKER[0]}" >/dev/null 2>&1
}

docker_container_exists() {
  local container="$1"
  "${DOCKER[@]}" inspect "${container}" >/dev/null 2>&1
}

docker_network_exists() {
  "${DOCKER[@]}" network inspect "${ONOS_DOCKER_NETWORK}" >/dev/null 2>&1
}

discover_onos_containers() {
  local container
  local names=()

  if [[ -n "${ONOS_CONTAINERS}" ]]; then
    read -r -a names <<< "$(tr ',' ' ' <<< "${ONOS_CONTAINERS}")"
  else
    names+=("${ONOS_CONTAINER}")
    if docker_available; then
      while IFS= read -r container; do
        names+=("${container}")
      done < <("${DOCKER[@]}" ps -a --format '{{.Names}}' 2>/dev/null |
        grep -E '^onos($|-)' || true)

      if docker_network_exists; then
        while IFS= read -r container; do
          names+=("${container}")
        done < <("${DOCKER[@]}" network inspect \
          -f '{{range .Containers}}{{println .Name}}{{end}}' \
          "${ONOS_DOCKER_NETWORK}" 2>/dev/null || true)
      fi
    fi
  fi

  printf '%s\n' "${names[@]}" | awk 'NF && !seen[$0]++'
}

clean_mininet() {
  if [[ "${CLEAN_MININET}" != "1" ]]; then
    echo "Skipping Mininet cleanup because CLEAN_MININET=${CLEAN_MININET}."
    return 0
  fi

  if [[ "${CLEAN_STALE_P4}" == "1" ]]; then
    echo "Stopping stale P4Runtime/Mininet processes..."
    run_allow_fail "${SUDO[@]}" pkill -f "multi_router_p4runtime.py"
    run_allow_fail "${SUDO[@]}" pkill -f "simple_switch_grpc"
  fi

  if command -v mn >/dev/null 2>&1; then
    echo "Cleaning Mininet state..."
    run "${SUDO[@]}" mn -c
  else
    echo "Skipping mn -c because mn is not in PATH."
  fi
}

clean_onos_docker() {
  local container
  local rm_args=(rm -f)

  if [[ "${CLEAN_ONOS_DOCKER}" != "1" ]]; then
    echo "Skipping ONOS Docker cleanup because CLEAN_ONOS_DOCKER=${CLEAN_ONOS_DOCKER}."
    return 0
  fi

  if ! docker_available; then
    echo "Skipping ONOS Docker cleanup because ${DOCKER[0]} is not in PATH."
    return 0
  fi

  if [[ "${REMOVE_ONOS_VOLUMES}" == "1" ]]; then
    rm_args+=(--volumes)
  fi

  echo "Removing ONOS Docker containers..."
  while IFS= read -r container; do
    if docker_container_exists "${container}"; then
      run "${DOCKER[@]}" "${rm_args[@]}" "${container}"
    else
      echo "Container ${container} does not exist."
    fi
  done < <(discover_onos_containers)

  if [[ "${CLEAN_ONOS_NETWORK}" != "1" ]]; then
    echo "Skipping ONOS Docker network cleanup because CLEAN_ONOS_NETWORK=${CLEAN_ONOS_NETWORK}."
    return 0
  fi

  if [[ "${ONOS_DOCKER_NETWORK}" == "bridge" ]]; then
    echo "Skipping Docker default bridge network."
    return 0
  fi

  if docker_network_exists; then
    echo "Removing Docker network ${ONOS_DOCKER_NETWORK}..."
    run "${DOCKER[@]}" network rm "${ONOS_DOCKER_NETWORK}"
  else
    echo "Docker network ${ONOS_DOCKER_NETWORK} does not exist."
  fi
}

echo "Cleaning ONOS/Mininet environment in ${ROOT_DIR}."
clean_mininet
clean_onos_docker
echo "Cleanup complete."
