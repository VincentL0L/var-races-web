#!/usr/bin/env bash
# Builds the simulation for the browser: crates/wasm -> web/src/wasm/pkg (JS glue, .wasm, types).
# Needs the wasm32 target and wasm-bindgen-cli of the same version as the wasm-bindgen crate:
#   rustup target add wasm32-unknown-unknown
#   cargo install wasm-bindgen-cli --version <version in Cargo.lock> --locked
# wasm-opt (binaryen) shrinks the result further when it's installed.
set -euo pipefail
cd "$(dirname "$0")/.."

out=web/src/wasm/pkg
bindgen=$(command -v wasm-bindgen || echo "${CARGO_HOME:-$HOME/.cargo}/bin/wasm-bindgen")
profile=${WASM_PROFILE:-wasm}

cargo build -q -p wasm --target wasm32-unknown-unknown --profile "$profile"
"$bindgen" --target web --out-dir "$out" "target/wasm32-unknown-unknown/$profile/wasm.wasm"
if command -v wasm-opt >/dev/null && [[ "$profile" == wasm ]]; then
    wasm-opt -Oz --enable-bulk-memory --enable-nontrapping-float-to-int --enable-sign-ext \
        --enable-mutable-globals -o "$out/wasm_bg.wasm" "$out/wasm_bg.wasm"
fi
# TypeScript quietly merges two declarations of the same name: every Rust type crossing into
# the browser needs a name of its own
dupes=$(grep -oE '^export (interface|type) [A-Za-z0-9_]+' "$out/wasm.d.ts" | awk '{print $3}' | sort | uniq -d)
if [[ -n "$dupes" ]]; then
    echo "wasm: two Rust types share the TypeScript name(s): $dupes" >&2
    exit 1
fi
echo "wasm: $(wc -c < "$out/wasm_bg.wasm" | tr -d ' ') bytes"
