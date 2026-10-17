#!/usr/bin/env bash
# Plays the game locally: builds the simulation for the browser and the web assets when they're
# missing or stale, then runs the server and the web client (Vite, which reloads as you edit).
#   npm run dev                  then open the address it prints
#   PORT=9000 WEB_PORT=3000 ...  other ports
# The server isn't rebuilt while it runs: after changing crates/server or crates/sim, stop and
# start again (and the browser's copy of crates/sim is rebuilt the same way).
set -euo pipefail
cd "$(dirname "$0")/.."

race_port="${PORT:-8080}"
web_port="${WEB_PORT:-8000}"

# something newer under these than the stamp file?
stale() {
    local stamp=$1
    shift
    [[ ! -e "$stamp" ]] || [[ -n "$(find "$@" -newer "$stamp" -print -quit)" ]]
}

if stale web/src/wasm/pkg/wasm_bg.wasm crates/sim crates/proto crates/wasm data; then
    bash tools/build-wasm.sh
fi
if stale web/src/gen/assets.ts assets tools/web-assets.mjs; then
    node tools/web-assets.mjs
fi
cargo build --release -p server

server_pid=""
cleanup() {
    [[ -z "$server_pid" ]] || kill "$server_pid" 2>/dev/null || true
}
trap cleanup EXIT

PORT="$race_port" target/release/var-races-server &
server_pid=$!

query=""
[[ "$race_port" == 8080 ]] || query="?server=ws://localhost:$race_port"
echo "Play locally: http://localhost:$web_port/$query"
WEB_PORT="$web_port" npx vite --config web/vite.config.ts --strictPort
