#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

MVN_CMD="${MVN_CMD:-mvn}"
MAVEN_ARGS=("$@")

if [ "${#MAVEN_ARGS[@]}" -eq 0 ]; then
  MAVEN_ARGS=(clean package -DskipTests)
fi

if [[ "${BUILD_P4:-0}" == "1" ]]; then
  ./tools/build_p4.sh
fi

if [[ "${USE_DOCKER:-0}" == "1" ]]; then
  DOCKER_CMD="${DOCKER_CMD:-docker}"
  MVN_IMG="${MVN_IMG:-maven:3.8-openjdk-8}"
  read -r -a DOCKER_CMD_ARR <<< "${DOCKER_CMD}"

  "${DOCKER_CMD_ARR[@]}" run --rm \
    -v "${PWD}:/mvn-src" \
    -w /mvn-src \
    "${MVN_IMG}" \
    mvn "${MAVEN_ARGS[@]}"
else
  if ! command -v "${MVN_CMD}" >/dev/null 2>&1; then
    echo "Maven command not found: ${MVN_CMD}" >&2
    echo "Install Maven or set MVN_CMD to the Maven executable path." >&2
    exit 127
  fi

  "${MVN_CMD}" "${MAVEN_ARGS[@]}"
fi

echo
echo "Build complete:"
ls -1 target/*.oar
