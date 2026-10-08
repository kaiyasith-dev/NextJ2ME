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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

public class GamePathsTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File work;
	private GamePaths paths;

	@Before
	public void setUp() throws IOException {
		work = tmp.newFolder("NextJ2ME");
		paths = new GamePaths(work);
	}

	@Test
	public void everythingOfAGameIsInOneFolder() {
		File game = new File(work, "games/Snake");
		assertEquals(game, paths.gameDir("Snake"));
		assertEquals(new File(game, "app"), paths.appDir("Snake"));
		assertEquals(new File(game, "saves"), paths.savesDir("Snake"));
		assertEquals(new File(game, "config"), paths.configDir("Snake"));
		assertEquals(new File(game, "debugger"), paths.debuggerDir("Snake"));
		assertEquals(new File(work, "games"), paths.gamesDir());
	}

	@Test
	public void theTemporaryFolderOfAnInstallIsInsideTheGameFolder() {
		// so that moving it over "app" is a rename inside one folder
		assertEquals(paths.appDir("Snake").getParentFile(), paths.tempAppDir("Snake").getParentFile());
	}

	@Test
	public void theGameIdAndTheWorkFolderCanBeFoundFromTheAppFolder() {
		File app = paths.appDir("Snake");
		assertEquals("Snake", GamePaths.gameOf(app));
		assertEquals(work, GamePaths.workDirOf(app));
		assertNull(GamePaths.workDirOf(new File("/app")));
		assertNull(GamePaths.gameOf(new File("/")));
	}

	@Test
	public void onlyTheShapeGamesGameAppHasAWorkFolder() {
		assertNull("a game folder of the older layout", GamePaths.workDirOf(new File(work, "converted/Snake")));
		assertNull("not called app", GamePaths.workDirOf(new File(work, "games/Snake/files")));
		assertNull("not inside games", GamePaths.workDirOf(new File(work, "other/Snake/app")));
		assertEquals(work, GamePaths.workDirOf(new File(work, "games/Snake/app")));
	}

	@Test
	public void onlyGamesWithAnInstalledAppAreListedAsInstalled() {
		assertTrue(paths.appDir("B").mkdirs());
		assertTrue(paths.appDir("A").mkdirs());
		assertTrue("only saves left", paths.savesDir("OnlySaves").mkdirs());
		assertTrue("hidden folders such as the temporary one are never games", new File(work, "games/.tmp").mkdirs());
		assertEquals(Arrays.asList("A", "B"), paths.installedGames());
		assertEquals(Arrays.asList("A", "B", "OnlySaves"), paths.allGameFolders());
	}

	@Test
	public void noGamesFolderMeansNoGames() {
		assertEquals(Collections.<String>emptyList(), paths.installedGames());
		assertEquals(Collections.<String>emptyList(), paths.allGameFolders());
	}

	@Test
	public void renamingAGameFolderMovesItsSavesSettingsAndDebuggerDataButNotTheApp() throws IOException {
		write(new File(paths.appDir("Old"), "converted.dex"), "dex");
		write(new File(paths.savesDir("Old"), "rms/1"), "save");
		write(new File(paths.configDir("Old"), "config.json"), "{}");
		write(new File(paths.debuggerDir("Old"), "memory.json"), "{}");
		write(new File(paths.savesDir("New"), "stale"), "older data of the target");

		paths.moveUserData("Old", "New");

		assertEquals("save", read(new File(paths.savesDir("New"), "rms/1")));
		assertFalse("the target's old data is replaced", new File(paths.savesDir("New"), "stale").exists());
		assertTrue(new File(paths.configDir("New"), "config.json").isFile());
		assertTrue(new File(paths.debuggerDir("New"), "memory.json").isFile());
		assertFalse("the old game folder is gone", paths.gameDir("Old").exists());
	}

	@Test
	public void movingToTheSameNameChangesNothing() throws IOException {
		write(new File(paths.savesDir("Same"), "x"), "1");
		assertTrue(paths.moveUserData("Same", "Same"));
		assertTrue(new File(paths.savesDir("Same"), "x").isFile());
	}

	@Test
	public void ifSomethingCanNotBeMovedTheOldFolderAndItsSavesAreKept() throws IOException {
		write(new File(paths.savesDir("Old"), "rms/1"), "save");
		write(new File(paths.configDir("Old"), "config.json"), "{}");
		// "New" is a plain file, so nothing can be created inside it
		write(paths.gameDir("New"), "in the way");

		assertFalse(paths.moveUserData("Old", "New"));

		assertEquals("save", read(new File(paths.savesDir("Old"), "rms/1")));
		assertTrue(new File(paths.configDir("Old"), "config.json").isFile());
	}

	@Test
	public void aNewGameGetsItsNameOrTheFirstFreeNumberedOne() {
		assertEquals("Snake", paths.uniqueGameId("Snake"));
		assertTrue(paths.appDir("Snake").mkdirs());
		assertEquals("Snake_1", paths.uniqueGameId("Snake"));
		assertTrue(paths.appDir("Snake_1").mkdirs());
		assertEquals("Snake_2", paths.uniqueGameId("Snake"));
	}

	@Test
	public void aFolderWithOnlySavesDoesNotCountAsTakenSoRestoredSavesAreReused() throws IOException {
		// e.g. a Saves-only backup was restored before the game was installed
		write(new File(paths.savesDir("Snake"), "rms/1"), "restored");
		assertEquals("Snake", paths.uniqueGameId("Snake"));
		assertEquals("restored", read(new File(paths.savesDir("Snake"), "rms/1")));
	}

	@Test
	public void cleanUpDropsAnUnfinishedInstallAndKeepsTheRest() throws IOException {
		write(new File(paths.appDir("Game"), "converted.dex"), "dex");
		write(new File(paths.savesDir("Game"), "x"), "1");
		write(new File(paths.tempAppDir("Game"), "half"), "unfinished");
		paths.cleanUp("Game");
		assertFalse(paths.tempAppDir("Game").exists());
		assertTrue(new File(paths.appDir("Game"), "converted.dex").isFile());
		assertTrue(new File(paths.savesDir("Game"), "x").isFile());
	}

	@Test
	public void cleanUpPutsBackTheAppOfAReinstallThatWasCutShort() throws IOException {
		// the old app was moved aside, the new one never arrived
		write(new File(paths.gameDir("Game"), ".old-app/converted.dex"), "old dex");
		write(new File(paths.savesDir("Game"), "x"), "1");
		paths.cleanUp("Game");
		assertEquals("old dex", read(new File(paths.appDir("Game"), "converted.dex")));
		assertFalse(new File(paths.gameDir("Game"), ".old-app").exists());
	}

	@Test
	public void cleanUpDropsTheLeftoverWhenTheNewAppIsAlreadyInPlace() throws IOException {
		write(new File(paths.gameDir("Game"), ".old-app/converted.dex"), "old dex");
		write(new File(paths.appDir("Game"), "converted.dex"), "new dex");
		paths.cleanUp("Game");
		assertEquals("new dex", read(new File(paths.appDir("Game"), "converted.dex")));
		assertFalse(new File(paths.gameDir("Game"), ".old-app").exists());
	}

	@Test
	public void cleanUpRemovesAnEmptyGameFolderButNeverOneWithContent() throws IOException {
		assertTrue(paths.gameDir("Empty").mkdirs());
		write(new File(paths.tempAppDir("OnlyTemp"), "half"), "x");
		write(new File(paths.savesDir("OnlySaves"), "x"), "1");
		paths.cleanUp("Empty");
		paths.cleanUp("OnlyTemp");
		paths.cleanUp("OnlySaves");
		assertFalse(paths.gameDir("Empty").exists());
		assertFalse("a cancelled first install leaves nothing", paths.gameDir("OnlyTemp").exists());
		assertTrue(new File(paths.savesDir("OnlySaves"), "x").isFile());
	}

	private static void write(File f, String text) throws IOException {
		//noinspection ResultOfMethodCallIgnored
		f.getParentFile().mkdirs();
		try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
			out.write(text.getBytes("UTF-8"));
		}
	}

	private static String read(File f) throws IOException {
		byte[] buf = new byte[256];
		try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
			return new String(buf, 0, in.read(buf), "UTF-8");
		}
	}
}
