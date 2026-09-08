#!/usr/bin/env bash
# Подставляет в gradle.properties актуальные версии Fabric для нужной версии игры.
# Использование:  ./scripts/fetch-fabric-versions.sh [версия_игры]
set -euo pipefail

MC="${1:-$(grep -Po '^minecraft_version=\K.*' gradle.properties)}"
META="https://meta.fabricmc.net/v2/versions"
API="https://api.modrinth.com/v2/project/fabric-api/version"

echo "Версия игры: $MC"

need() { command -v "$1" >/dev/null || { echo "нужен $1" >&2; exit 1; }; }
need curl; need jq

YARN=$(curl -fsSL "$META/yarn/$MC"   | jq -r '.[0].version')
LOADER=$(curl -fsSL "$META/loader"   | jq -r '[.[] | select(.stable)][0].version')
FAPI=$(curl -fsSL "$API?game_versions=%5B%22$MC%22%5D&loaders=%5B%22fabric%22%5D" \
        | jq -r '.[0].version_number')

echo "yarn:         $YARN"
echo "loader:       $LOADER"
echo "fabric-api:   $FAPI"
echo
echo "Версию Loom сверьте на https://fabricmc.net/develop/ — она не лежит в meta API."

sed -i.bak \
  -e "s|^minecraft_version=.*|minecraft_version=$MC|" \
  -e "s|^yarn_mappings=.*|yarn_mappings=$YARN|" \
  -e "s|^loader_version=.*|loader_version=$LOADER|" \
  -e "s|^fabric_api_version=.*|fabric_api_version=$FAPI|" \
  gradle.properties

echo "gradle.properties обновлён (бэкап в gradle.properties.bak)"
