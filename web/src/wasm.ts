// The simulation and protocol, compiled from Rust (crates/wasm). Call loadSim() once before
// using anything else from here.
import init from './wasm/pkg/wasm.js';
import wasmUrl from './wasm/pkg/wasm_bg.wasm?url';

export * from './wasm/pkg/wasm.js';

export async function loadSim(): Promise<void> {
  await init({ module_or_path: wasmUrl });
}
