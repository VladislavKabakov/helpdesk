#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if docker compose version >/dev/null 2>&1; then
    exec docker compose "$@"
elif command -v docker-compose >/dev/null 2>&1; then
    exec docker-compose "$@"
else
    echo 'Нужен Docker Compose v2: docker compose или docker-compose.' >&2
    exit 1
fi
