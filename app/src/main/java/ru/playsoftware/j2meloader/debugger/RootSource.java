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

import java.util.Map;

/**
 * Tells the debugger where the game state starts. This is the only seam between the
 * debugger core and the emulator, which keeps the core free of Android dependencies.
 */
public interface RootSource {
	/**
	 * Live root objects by stable name (for example {@code "midlet"} and {@code "displayable"}).
	 * Called on every enumeration; implementations must not cache the objects themselves.
	 */
	Map<String, Object> namedRoots();

	/** Whether {@code c} belongs to the game (as opposed to the emulator or the platform). */
	boolean isAppClass(Class<?> c);
}
