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

package ru.playsoftware.j2meloader.util;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where everything that belongs to one game is, in one place.
 * <pre>
 * &lt;work folder&gt;/games/&lt;game&gt;/
 *     app/        the installed game: converted.dex, res.jar, icon.png, descriptor (can be rebuilt)
 *     saves/      the game's saves: the default slot, and slots/&lt;name&gt; (see {@link SaveSlots})
 *     config/     the game's settings and key layout
 *     debugger/   the memory debugger's data for the game
 * </pre>
 * The folder name {@code <game>} is the game's id (the {@code path} of its list entry). Keeping the
 * files that can be rebuilt ({@code app}) apart from the ones that can not lets a reinstall replace
 * {@code app} without ever touching saves or settings.
 * <p>
 * Plain {@code java.io}: the emulator process and the main app both use it.
 */
public final class GamePaths {
	private static final String GAMES = "games";
	private static final String APP = "app";
	private static final String SAVES = "saves";
	private static final String CONFIG = "config";
	private static final String DEBUGGER = "debugger";
	private static final String TEMP = ".tmp";

	private final File workDir;

	/** @param workDir the emulator work folder */
	public GamePaths(File workDir) {
		this.workDir = workDir;
	}

	/** Folder that holds all the games. */
	public File gamesDir() {
		return new File(workDir, GAMES);
	}

	/** Everything of one game. */
	public File gameDir(String game) {
		return new File(gamesDir(), game);
	}

	/** The installed game: its files can be deleted and rebuilt from {@code res.jar}. */
	public File appDir(String game) {
		return new File(gameDir(game), APP);
	}

	/** The saves of the game (all its save slots). */
	public File savesDir(String game) {
		return new File(gameDir(game), SAVES);
	}

	/** The game's settings and key layout. */
	public File configDir(String game) {
		return new File(gameDir(game), CONFIG);
	}

	/** The memory debugger's data for the game. */
	public File debuggerDir(String game) {
		return new File(gameDir(game), DEBUGGER);
	}

	/** Where a new copy of a game's {@code app} folder is built before it replaces the old one. */
	public File tempAppDir(String game) {
		return new File(gameDir(game), TEMP);
	}

	/** The ids of the games that have an installed {@code app} folder, sorted. */
	public List<String> installedGames() {
		String[] names = gamesDir().list();
		List<String> out = new ArrayList<>();
		if (names != null) {
			for (String name : names) {
				if (!name.startsWith(".") && appDir(name).isDirectory()) {
					out.add(name);
				}
			}
		}
		Collections.sort(out);
		return out;
	}

	/** The ids of all the folders in {@code games}, installed or not (a game may only have saves left). */
	public List<String> allGameFolders() {
		String[] names = gamesDir().list();
		List<String> out = new ArrayList<>();
		if (names != null) {
			for (String name : names) {
				if (!name.startsWith(".") && gameDir(name).isDirectory()) {
					out.add(name);
				}
			}
		}
		Collections.sort(out);
		return out;
	}

	/**
	 * Moves the saves, settings and debugger data of a game to the folder of another game id and
	 * removes what is left of the old game folder. Used when a game's folder name changes.
	 *
	 * @return false if something could not be moved; the old folder is then left as it is, so
	 * nothing is lost
	 */
	public boolean moveUserData(String from, String to) {
		if (from.equals(to)) {
			return true;
		}
		File[] fromDirs = {savesDir(from), configDir(from), debuggerDir(from)};
		File[] toDirs = {savesDir(to), configDir(to), debuggerDir(to)};
		boolean allMoved = true;
		for (int i = 0; i < fromDirs.length; i++) {
			if (!fromDirs[i].exists()) {
				continue;
			}
			FileUtils.deleteDirectory(toDirs[i]); // older data of the target game is replaced
			File parent = toDirs[i].getParentFile();
			if (parent != null) {
				//noinspection ResultOfMethodCallIgnored
				parent.mkdirs();
			}
			if (!fromDirs[i].renameTo(toDirs[i])) {
				allMoved = false;
			}
		}
		if (allMoved) {
			FileUtils.deleteDirectory(gameDir(from));
		}
		return allMoved;
	}

	/**
	 * The id for a new game called {@code name}: the name itself, or the first of name_1, name_2...
	 * that has no installed app. A folder that only holds saves or settings (for example restored
	 * from a backup before the game was installed) does not count as taken, so the game picks them up.
	 */
	public String uniqueGameId(String name) {
		String id = name;
		for (int i = 1; appDir(id).exists(); i++) {
			id = name + "_" + i;
		}
		return id;
	}

	/**
	 * Cleans up one game folder at startup: drops the unfinished copy of an install, puts back the
	 * app folder of a reinstall that was cut short, and removes the folder if nothing is left in it.
	 */
	public void cleanUp(String game) {
		File temp = tempAppDir(game);
		if (temp.exists()) {
			FileUtils.deleteDirectory(temp);
		}
		FileUtils.recoverReplaced(gameDir(game));
		// a cancelled first install leaves an empty folder; this fails, and keeps the folder, if
		// there is anything in it
		//noinspection ResultOfMethodCallIgnored
		gameDir(game).delete();
	}

	// ------------------------------------------------------------------ from an app folder

	/**
	 * The work folder of an app folder ({@code <work>/games/<game>/app}), or null if the path does
	 * not have that shape.
	 */
	@Nullable
	public static File workDirOf(File appDir) {
		if (!APP.equals(appDir.getName())) {
			return null; // for example a game folder of an older layout
		}
		File game = appDir.getParentFile();
		File games = game == null ? null : game.getParentFile();
		if (games == null || !GAMES.equals(games.getName())) {
			return null;
		}
		return games.getParentFile();
	}

	/** The id of the game of an app folder ({@code <work>/games/<game>/app}), or null. */
	@Nullable
	public static String gameOf(File appDir) {
		File game = appDir.getParentFile();
		return game == null ? null : game.getName();
	}
}
