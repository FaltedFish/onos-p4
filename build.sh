#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

DOCKER_CMD="${DOCKER_CMD:-docker}"
MVN_IMG="${MVN_IMG:-maven:3.8-openjdk-8}"

${DOCKER_CMD} run --rm \
  -v "${PWD}:/mvn-src" \
  -w /mvn-src \
  "${MVN_IMG}" \
  mvn clean package -DskipTests

echo
echo "Build complete:"
ls -1 target/*.oar
