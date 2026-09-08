#!/usr/bin/env bash
# Подставляет в gradle.properties актуальную версию yarn для нужной версии игры.
# Остальные версии заданы в gradle.properties и проверены — их скрипт не трогает.
# Нужен только curl: jq намеренно не используется, его нет ни на раннере CI,
# ни у половины пользователей.
#
#   ./scripts/fetch-fabric-versions.sh [версия_игры]
set -euo pipefail

MC="${1:-$(grep -E '^minecraft_version=' gradle.properties | cut -d= -f2)}"
META="https://meta.fabricmc.net/v2/versions"

command -v curl >/dev/null || { echo "нужен curl" >&2; exit 1; }

echo "Версия игры: $MC"

# Ответ meta приходит одной длинной строкой, поэтому вытаскиваем все совпадения
# и берём первое — оно самое свежее.
#
# sed, а не head: head закрывает канал после первой строки, grep получает SIGPIPE
# и завершается ненулевым кодом, а под `set -o pipefail` это роняет весь скрипт.
# sed дочитывает поток до конца, поэтому канал не рвётся.
YARN=$(curl -fsSL "$META/yarn/$MC" \
    | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' \
    | sed -n '1s/.*"\([^"]*\)"$/\1/p')

[ -n "$YARN" ] || { echo "не удалось узнать версию yarn для $MC" >&2; exit 1; }

echo "yarn: $YARN"

sed -i \
    -e "s|^minecraft_version=.*|minecraft_version=$MC|" \
    -e "s|^yarn_mappings=.*|yarn_mappings=$YARN|" \
    gradle.properties

echo
echo "Остальные версии оставлены как есть:"
grep -E '^(loader_version|loom_version|fabric_api_version)=' gradle.properties | sed 's/^/  /'
echo
echo "gradle.properties обновлён."
