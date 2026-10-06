/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Memory debugger: scan, watch, edit and freeze the state of the running J2ME game.
 *
 * <h2>What "memory" means here</h2>
 * J2ME-Loader does not interpret bytecode. A game's JAR is converted to DEX and runs as ordinary
 * Java objects on ART, so there is no emulated heap with raw addresses. The debugger therefore
 * inspects the game's real Java state through four scopes ({@link
 * ru.playsoftware.j2meloader.debugger.ScanScope}), each backed by a {@link
 * ru.playsoftware.j2meloader.debugger.MemoryProvider}:
 * <ul>
 * <li>static fields of the game's classes ({@code StaticFieldProvider}),</li>
 * <li>instance fields of live objects reachable from the roots ({@code VmObjectProvider}),</li>
 * <li>elements of primitive arrays ({@code ArrayProvider}),</li>
 * <li>a virtual byte-addressable space made of the big-endian images of those arrays
 * ({@code RawMemoryProvider}); the closest thing to raw memory a J2ME app has.</li>
 * </ul>
 * Nothing here touches the Android process memory, and no host pointer or identity hash is ever
 * shown: objects get ids from the {@link ru.playsoftware.j2meloader.debugger.ObjectRegistry},
 * arrays get virtual addresses from the {@link ru.playsoftware.j2meloader.debugger.AddressSpace}.
 *
 * <h2>Layers</h2>
 * <pre>
 * MemoryDebugger        facade: lifecycle, sessions, read/write, watches, freezes, cheats, persistence
 *   MemoryScanner       first scan + snapshot based filtering (MemorySnapshot / ScanSession)
 *   MemoryProvider      per scope source of MemoryRegion (static, object, array, raw)
 *     VmInspector       reflection walk of the object graph, path finding, reference resolution
 *       MemoryRegion    FieldRegion / ArrayRegion: typed or byte addressable view, weakly referenced
 *   FreezeEngine        single low-rate timer thread, alive only while something is enforced
 *   PauseGate           cooperative pause: game threads park at render / event checkpoints
 *   DebuggerStore       per-game JSON (watches, freezes, cheats, settings) - never scan results
 * </pre>
 * The core has no Android dependency; {@code ui.EmulatorBridge} is the only class that knows the
 * emulator (roots and "what is a game class") and {@code ui.MemoryDebuggerDialog} is the UI.
 *
 * <h2>Stability of references</h2>
 * A scan result points at a {@link ru.playsoftware.j2meloader.debugger.MemoryLocation} (region id
 * plus slot), valid for one run of the game. To keep a value across object replacement and restarts,
 * a {@link ru.playsoftware.j2meloader.debugger.MemoryReference} describes it symbolically, for
 * example {@code com.example.Game.player.hp}, and is re-resolved on every access. Only symbolic
 * references are persisted.
 *
 * <h2>Cost when unused</h2>
 * The master switch decides at game start whether a {@code MemoryDebugger} exists at all. When it
 * does not, the emulator hooks are a null check (class loading, lifecycle) or one volatile read
 * ({@code PauseGate.checkpoint}). Regions are weak views, and ids or addresses are assigned only to
 * regions that yield a candidate or are looked at.
 */
package ru.playsoftware.j2meloader.debugger;
