/*
 * Copyright 2026 ksdevla
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

package ru.playsoftware.j2meloader.backup;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** What goes into a backup. Folders are relative to the emulator work folder. */
public enum BackupScope {
	/** Game saves, per-game settings, profiles and debugger data: small. */
	SAVES("saves", "configs", "templates", "fs", "debugger"),
	/** Everything above plus the installed games: can be large. */
	ALL("saves", "configs", "templates", "fs", "debugger", "converted", "shaders");

	private final String[] dirs;

	BackupScope(String... dirs) {
		this.dirs = dirs;
	}

	public String[] dirs() {
		return dirs.clone();
	}

	/** Top-level folders a restore may write to, whatever scope the backup was made with. */
	static final Set<String> RESTORABLE = new HashSet<>(Arrays.asList(ALL.dirs));
}
