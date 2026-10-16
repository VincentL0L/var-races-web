#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Optional machine-local Java/Gradle paths; no npm dependencies are needed.
if [[ -f .env.local ]]; then
    source .env.local
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.gradle-local}"
gradle_bin="${GRADLE_BIN:-./gradlew}"
web_port="${WEB_PORT:-8000}"
race_port="${PORT:-8080}"

"$gradle_bin" core:test teavm:buildJavaScript server:installDist
if [[ "${1:-}" == "--build-only" ]]; then
    exit 0
fi

server_pid=""
web_pid=""
cleanup() {
    trap - EXIT INT TERM
    [[ -z "$web_pid" ]] || kill "$web_pid" 2>/dev/null || true
    [[ -z "$server_pid" ]] || kill "$server_pid" 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

PORT="$race_port" server/build/install/server/bin/server &
server_pid=$!
python3 -m http.server "$web_port" --bind 127.0.0.1 --directory teavm/build/dist/webapp &
web_pid=$!
echo "Play locally: http://localhost:$web_port/?server=ws://localhost:$race_port"
wait -n "$server_pid" "$web_pid"
