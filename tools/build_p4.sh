#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

P4C_CMD="${P4C_CMD:-p4c}"
P4_INCLUDE_DIR="${P4_INCLUDE_DIR:-/usr/share/p4c/p4include}"
P4_NAME="${P4_NAME:-ngsdn_tutorial}"
P4_SRC="${P4_SRC:-p4src/${P4_NAME}.p4}"
P4_BUILD_DIR="${P4_BUILD_DIR:-p4build}"
P4_OUT_DIR="${P4_BUILD_DIR}/${P4_NAME}"
P4INFO_OUT="${P4_BUILD_DIR}/${P4_NAME}.p4info.pb.txt"

BMV2_RESOURCE="src/main/resources/bmv2.json"
P4INFO_RESOURCE="src/main/resources/p4info.txt"

if ! command -v "${P4C_CMD}" >/dev/null 2>&1; then
  echo "P4 compiler not found: ${P4C_CMD}" >&2
  echo "Install p4c or set P4C_CMD to the compiler executable path." >&2
  exit 127
fi

if [ ! -f "${P4_SRC}" ]; then
  echo "P4 source not found: ${P4_SRC}" >&2
  exit 1
fi

mkdir -p "${P4_BUILD_DIR}"

"${P4C_CMD}" \
  --target bmv2 \
  --arch v1model \
  --std p4-16 \
  -I "${P4_INCLUDE_DIR}" \
  -o "${P4_OUT_DIR}" \
  --p4runtime-files "${P4INFO_OUT}" \
  "${P4_SRC}"

cp -f "${P4_OUT_DIR}/${P4_NAME}.json" "${BMV2_RESOURCE}"
cp -f "${P4INFO_OUT}" "${P4INFO_RESOURCE}"

echo
echo "P4 build complete:"
echo "  BMv2 JSON: ${P4_OUT_DIR}/${P4_NAME}.json"
echo "  P4Info:    ${P4INFO_OUT}"
echo "  Synced:    ${BMV2_RESOURCE}"
echo "  Synced:    ${P4INFO_RESOURCE}"
