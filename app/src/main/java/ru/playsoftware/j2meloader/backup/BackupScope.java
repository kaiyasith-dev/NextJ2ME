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

/**
 * What goes into a backup. Paths are relative to the emulator work folder. Besides a few shared
 * folders, every game has its own folder {@code games/<game>} with the subfolders {@code app}
 * (the installed game, can be rebuilt), {@code saves}, {@code config} and {@code debugger}.
 */
public enum BackupScope {
	/** Game saves, per-game settings, profiles and debugger data: small. */
	SAVES(new String[]{"templates", "fs"}, new String[]{"saves", "config", "debugger"}),
	/** Everything above plus the installed games: can be large. */
	ALL(new String[]{"templates", "fs", "shaders"}, new String[]{"app", "saves", "config", "debugger"});

	/** The folder that holds the games. */
	static final String GAMES = "games";

	/** Shared top-level folders a restore may write to, whatever scope the backup was made with. */
	static final Set<String> RESTORABLE_SHARED = new HashSet<>(Arrays.asList(ALL.shared));
	/** Subfolders of a game a restore may write to. */
	static final Set<String> RESTORABLE_GAME_DIRS = new HashSet<>(Arrays.asList(ALL.perGame));

	private final String[] shared;
	private final String[] perGame;

	BackupScope(String[] shared, String[] perGame) {
		this.shared = shared;
		this.perGame = perGame;
	}

	/** Top-level folders outside {@code games} that are included. */
	public String[] sharedDirs() {
		return shared.clone();
	}

	/** Subfolders of every game that are included. */
	public String[] gameDirs() {
		return perGame.clone();
	}
}
