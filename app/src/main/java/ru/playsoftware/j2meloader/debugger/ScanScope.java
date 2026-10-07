/*
 * Copyright 2026 ksdev
 *
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

package ru.playsoftware.j2meloader.debugger;

/**
 * Where a scan looks. J2ME-Loader has no VM of its own: games are converted to DEX and run
 * on ART, so these scopes describe slices of the real Java state of the running game.
 */
public enum ScanScope {
	/** Virtual byte-addressable space built from the game's primitive arrays (big-endian images). */
	RAW("Raw memory"),
	/** Primitive fields of live objects reachable from the game's roots. */
	OBJECTS("Java objects"),
	/** Non-final static fields of the game's loaded classes. */
	STATIC_FIELDS("Static fields"),
	/** Elements of primitive arrays reachable from the game's roots, typed per element. */
	ARRAYS("Primitive arrays");

	private final String label;

	ScanScope(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
