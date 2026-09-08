#!/usr/bin/env bash
# Подставляет в gradle.properties актуальные версии Fabric для нужной версии игры.
# Нужен только curl: jq намеренно не используется, его нет ни на раннере CI,
# ни у половины пользователей.
#
#   ./scripts/fetch-fabric-versions.sh [версия_игры]
set -euo pipefail

MC="${1:-$(grep -E '^minecraft_version=' gradle.properties | cut -d= -f2)}"
META="https://meta.fabricmc.net/v2/versions"

command -v curl >/dev/null || { echo "нужен curl" >&2; exit 1; }

echo "Версия игры: $MC"

# Первое значение "version" в JSON-массиве — самое свежее.
first_version() {
    grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' \
        | head -1 | sed 's/.*"\([^"]*\)"$/\1/'
}

YARN=$(curl -fsSL "$META/yarn/$MC" | first_version)
LOADER=$(curl -fsSL "$META/loader" | first_version)

[ -n "$YARN" ]   || { echo "не удалось узнать версию yarn для $MC" >&2; exit 1; }
[ -n "$LOADER" ] || { echo "не удалось узнать версию loader" >&2; exit 1; }

echo "yarn:   $YARN"
echo "loader: $LOADER"
echo
echo "Версии Loom и Fabric API берутся из gradle.properties как есть:"
echo "  loom:       $(grep -E '^loom_version=' gradle.properties | cut -d= -f2)"
echo "  fabric-api: $(grep -E '^fabric_api_version=' gradle.properties | cut -d= -f2)"
echo "Свериться можно на https://fabricmc.net/develop/"

sed -i.bak \
    -e "s|^minecraft_version=.*|minecraft_version=$MC|" \
    -e "s|^yarn_mappings=.*|yarn_mappings=$YARN|" \
    gradle.properties
rm -f gradle.properties.bak

echo
echo "gradle.properties обновлён."
