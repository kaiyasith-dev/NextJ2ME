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

package ru.playsoftware.j2meloader.debugger;

/** Per-game debugger preferences that are saved with the game's cheats and watches. */
public final class DebuggerSettings {
	/** Last used scan configuration (restored into the Scan tab). */
	public ScanParams scan = new ScanParams();
	/** Interval of the freeze timer in milliseconds. */
	public int freezePeriodMs = 100;
}
