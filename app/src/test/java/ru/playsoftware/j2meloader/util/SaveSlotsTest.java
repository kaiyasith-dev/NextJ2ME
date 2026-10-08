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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

public class SaveSlotsTest {
	private static final String GAME = "Snake";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File work;
	private SaveSlots slots;

	@Before
	public void setUp() throws IOException {
		work = tmp.newFolder("NextJ2ME");
		slots = new SaveSlots(work);
	}

	private static void write(File f, String text) throws IOException {
		//noinspection ResultOfMethodCallIgnored
		f.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(text.getBytes("UTF-8"));
		}
	}

	private static String read(File f) throws IOException {
		byte[] buf = new byte[256];
		try (FileInputStream in = new FileInputStream(f)) {
			int n = in.read(buf);
			return new String(buf, 0, n, "UTF-8");
		}
	}

	private static String unix(File f) {
		return f.getPath().replace('\\', '/');
	}

	// ------------------------------------------------------------ where the saves are

	@Test
	public void theDefaultSlotIsTheGamesSavesFolderItself() {
		assertEquals(new File(work, "saves/Snake"), slots.dir(GAME, SaveSlots.DEFAULT));
		assertEquals(new File(work, "saves/Snake"), slots.dir(GAME, null));
		assertEquals(unix(work) + "/saves/" + GAME, unix(slots.activeDir(GAME)));
		assertEquals(slots.gameDir(GAME), slots.activeDir(GAME));
	}

	@Test
	public void theOtherSlotsAreInASlotsFolderInsideIt() {
		assertEquals(new File(work, "saves/Snake/slots/Level 5"), slots.dir(GAME, "Level 5"));
		assertEquals(new File(work, "saves/Snake/slots"), slots.slotsDir(GAME));
	}

	@Test
	public void aGameWithoutSlotsUsesTheDefaultAndNothingIsCreated() {
		assertEquals(SaveSlots.DEFAULT, slots.active(GAME));
		assertTrue(slots.list(GAME).isEmpty());
		assertFalse("asking does not create folders", new File(work, "saves").exists());
	}

	// ------------------------------------------------------------ making and choosing slots

	@Test
	public void aNewSlotIsEmptyAndListed() throws IOException {
		assertEquals("Alice", slots.create(GAME, "Alice"));
		assertTrue(slots.dir(GAME, "Alice").isDirectory());
		assertEquals(0, slots.dir(GAME, "Alice").list().length);
		assertEquals(Collections.singletonList("Alice"), slots.list(GAME));
	}

	@Test
	public void slotsAreListedAlphabetically() throws IOException {
		slots.create(GAME, "bob");
		slots.create(GAME, "Alice");
		slots.create(GAME, "carl");
		assertEquals(Arrays.asList("Alice", "bob", "carl"), slots.list(GAME));
	}

	@Test
	public void theGamesOwnFoldersAreNeverListedAsSlots() throws IOException {
		// a game saves files and folders of its own next to the slots folder
		write(new File(work, "saves/Snake/score-h.db"), "1");
		write(new File(work, "saves/Snake/private/save.bin"), "2");
		write(new File(work, "saves/Snake/cache/tmp"), "3");
		assertTrue(slots.list(GAME).isEmpty());
		slots.create(GAME, "A");
		assertEquals(Collections.singletonList("A"), slots.list(GAME));
	}

	@Test
	public void theActiveSlotIsRememberedAndSurvivesANewObject() throws IOException {
		slots.create(GAME, "Alice");
		slots.setActive(GAME, "Alice");
		assertEquals("Alice", slots.active(GAME));
		assertEquals(slots.dir(GAME, "Alice"), slots.activeDir(GAME));
		// the game process builds its own object and must see the same
		assertEquals("Alice", new SaveSlots(work).active(GAME));
		slots.setActive(GAME, SaveSlots.DEFAULT);
		assertEquals(SaveSlots.DEFAULT, slots.active(GAME));
	}

	@Test
	public void eachGameHasItsOwnActiveSlot() throws IOException {
		slots.create(GAME, "A");
		slots.create("Tetris", "A");
		slots.setActive(GAME, "A");
		assertEquals("A", slots.active(GAME));
		assertEquals(SaveSlots.DEFAULT, slots.active("Tetris"));
	}

	@Test
	public void aSlotThatDoesNotExistCanNotBecomeActive() {
		try {
			slots.setActive(GAME, "Ghost");
			fail("expected an IOException");
		} catch (IOException expected) {
			assertEquals(SaveSlots.DEFAULT, slots.active(GAME));
		}
	}

	@Test
	public void ifTheActiveSlotIsGoneTheGameFallsBackToTheDefault() throws IOException {
		slots.create(GAME, "Alice");
		slots.setActive(GAME, "Alice");
		assertTrue(slots.dir(GAME, "Alice").delete()); // removed behind our back
		assertEquals(SaveSlots.DEFAULT, slots.active(GAME));
	}

	@Test
	public void aDamagedActiveFileMeansTheDefault() throws IOException {
		slots.create(GAME, "Alice");
		File active = new File(work, "saves/Snake/.active");
		write(active, "../../data/Other");
		assertEquals(SaveSlots.DEFAULT, slots.active(GAME));
		write(active, "");
		assertEquals(SaveSlots.DEFAULT, slots.active(GAME));
		write(active, "Alice");
		assertEquals("Alice", slots.active(GAME));
	}

	// ------------------------------------------------------------ names

	@Test
	public void namesAreCleanedUp() throws IOException {
		assertEquals("My save", slots.create(GAME, "  My save  "));
		assertEquals("ab", slots.create(GAME, "a/b"));
		assertEquals("hidden", slots.create(GAME, ".hidden"));
		String longName = new String(new char[100]).replace('\0', 'x');
		assertEquals(40, slots.create(GAME, longName).length());
	}

	@Test
	public void pathTricksCanNotLeaveTheSlotsFolder() throws IOException {
		String stored = slots.create(GAME, "../../data/Other");
		assertFalse(stored.contains("/"));
		assertTrue(slots.dir(GAME, stored).getCanonicalPath().startsWith(
				new File(work, "saves/Snake/slots").getCanonicalPath()));
		assertFalse(new File(work, "data/Other").exists());
	}

	@Test
	public void emptyOrReservedNamesAreRefused() throws IOException {
		for (String bad : new String[]{"", "   ", "///", "...", "Default", "default", " DEFAULT "}) {
			try {
				slots.create(GAME, bad);
				fail("expected a refusal for '" + bad + "'");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
			}
		}
		assertTrue(slots.list(GAME).isEmpty());
	}

	@Test
	public void aNameThatIsTakenIsRefusedEvenInAnotherCase() throws IOException {
		slots.create(GAME, "Alice");
		try {
			slots.create(GAME, "alice");
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("Alice"));
		}
		assertEquals(1, slots.list(GAME).size());
	}

	// ------------------------------------------------------------ copying, renaming, deleting

	@Test
	public void duplicatingTheDefaultCopiesItsSavesButNotTheSlotsFolderOrTheChoice() throws IOException {
		write(new File(work, "saves/Snake/score-h.db"), "header");
		write(new File(work, "saves/Snake/private/save.bin"), "deep");
		slots.create(GAME, "Other");
		write(new File(slots.dir(GAME, "Other"), "o"), "other slot");
		slots.setActive(GAME, "Other");

		assertEquals("Copy", slots.duplicate(GAME, SaveSlots.DEFAULT, "Copy"));

		File copy = slots.dir(GAME, "Copy");
		assertEquals("header", read(new File(copy, "score-h.db")));
		assertEquals("deep", read(new File(copy, "private/save.bin")));
		assertFalse("the copy has no slots inside it", new File(copy, "slots").exists());
		assertFalse(new File(copy, ".active").exists());
		assertEquals("source untouched", "header", read(new File(work, "saves/Snake/score-h.db")));
		assertEquals(Arrays.asList("Copy", "Other"), slots.list(GAME));
		for (String name : slots.slotsDir(GAME).list()) {
			assertFalse("no temporary folders left: " + name, name.endsWith(".tmp"));
		}
	}

	@Test
	public void duplicatingADefaultSlotThatHasNoSavesGivesAnEmptySlot() throws IOException {
		slots.duplicate(GAME, SaveSlots.DEFAULT, "Empty");
		assertTrue(slots.dir(GAME, "Empty").isDirectory());
		assertEquals(0, slots.dir(GAME, "Empty").list().length);
	}

	@Test
	public void duplicatingAnotherSlotWorks() throws IOException {
		slots.create(GAME, "A");
		write(new File(slots.dir(GAME, "A"), "x"), "1");
		slots.duplicate(GAME, "A", "B");
		assertEquals("1", read(new File(slots.dir(GAME, "B"), "x")));
	}

	@Test
	public void renamingKeepsTheFilesAndTheActiveChoice() throws IOException {
		slots.create(GAME, "Old");
		write(new File(slots.dir(GAME, "Old"), "x"), "1");
		slots.setActive(GAME, "Old");
		assertEquals("New", slots.rename(GAME, "Old", "New"));
		assertEquals(Collections.singletonList("New"), slots.list(GAME));
		assertEquals("1", read(new File(slots.dir(GAME, "New"), "x")));
		assertEquals("New", slots.active(GAME));
	}

	@Test
	public void renamingCanOnlyChangeTheCase() throws IOException {
		slots.create(GAME, "alice");
		assertEquals("Alice", slots.rename(GAME, "alice", "Alice"));
		assertEquals(Collections.singletonList("Alice"), slots.list(GAME));
	}

	@Test
	public void renamingToATakenNameOrRenamingTheDefaultIsRefused() throws IOException {
		slots.create(GAME, "A");
		slots.create(GAME, "B");
		try {
			slots.rename(GAME, "A", "b");
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			// ok
		}
		try {
			slots.rename(GAME, SaveSlots.DEFAULT, "X");
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			// ok
		}
		assertEquals(Arrays.asList("A", "B"), slots.list(GAME));
	}

	@Test
	public void deletingRemovesTheSavesAndFallsBackToTheDefaultIfActive() throws IOException {
		slots.create(GAME, "A");
		write(new File(slots.dir(GAME, "A"), "x"), "1");
		slots.setActive(GAME, "A");
		slots.delete(GAME, "A");
		assertFalse(slots.dir(GAME, "A").exists());
		assertEquals(SaveSlots.DEFAULT, slots.active(GAME));
		assertTrue(slots.list(GAME).isEmpty());
	}

	@Test
	public void theDefaultSlotCanNotBeDeleted() throws IOException {
		write(new File(work, "saves/Snake/keep"), "1");
		try {
			slots.delete(GAME, SaveSlots.DEFAULT);
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			assertTrue(new File(work, "saves/Snake/keep").isFile());
		}
	}

	// ------------------------------------------------------------ clearing

	@Test
	public void clearingTheDefaultKeepsTheOtherSlotsAndTheChoice() throws IOException {
		write(new File(work, "saves/Snake/score-h.db"), "1");
		write(new File(work, "saves/Snake/private/save.bin"), "2");
		slots.create(GAME, "A");
		write(new File(slots.dir(GAME, "A"), "x"), "slot data");
		slots.setActive(GAME, "A");

		slots.clear(GAME, SaveSlots.DEFAULT);

		assertFalse(new File(work, "saves/Snake/score-h.db").exists());
		assertFalse(new File(work, "saves/Snake/private").exists());
		assertEquals("slot data", read(new File(slots.dir(GAME, "A"), "x")));
		assertEquals("A", slots.active(GAME));
		assertTrue(slots.isEmpty(GAME, SaveSlots.DEFAULT));
	}

	@Test
	public void clearingASlotKeepsTheSlotItselfAndTheOthers() throws IOException {
		write(new File(work, "saves/Snake/keep"), "default");
		slots.create(GAME, "A");
		write(new File(slots.dir(GAME, "A"), "x"), "1");
		write(new File(slots.dir(GAME, "A"), "sub/y"), "2");
		slots.setActive(GAME, "A");

		slots.clear(GAME, "A");

		assertTrue("the slot is still there and still chosen", slots.dir(GAME, "A").isDirectory());
		assertEquals("A", slots.active(GAME));
		assertTrue(slots.isEmpty(GAME, "A"));
		assertEquals("default", read(new File(work, "saves/Snake/keep")));
	}

	@Test
	public void theDefaultCountsAsEmptyDespiteTheSlotsInsideIt() throws IOException {
		slots.create(GAME, "A");
		assertTrue(slots.isEmpty(GAME, SaveSlots.DEFAULT));
		write(new File(work, "saves/Snake/x"), "1");
		assertFalse(slots.isEmpty(GAME, SaveSlots.DEFAULT));
		assertTrue("a slot that does not exist is empty", slots.isEmpty(GAME, "Ghost"));
	}

	// ------------------------------------------------------------ sizes and the whole game

	@Test
	public void aSlotsSizeCountsItsOwnFilesOnly() throws IOException {
		write(new File(work, "saves/Snake/a"), "12345");
		slots.create(GAME, "A");
		write(new File(slots.dir(GAME, "A"), "b"), "12");
		write(new File(slots.dir(GAME, "A"), "sub/c"), "123");
		assertEquals("the default does not count the slots inside it", 5, slots.size(GAME, SaveSlots.DEFAULT));
		assertEquals(5, slots.size(GAME, "A"));
		assertEquals(0, slots.size(GAME, "Missing"));
	}

	@Test
	public void deletingAGameRemovesAllItsSavesButNotOtherGames() throws IOException {
		write(new File(work, "saves/Snake/x"), "1");
		slots.create(GAME, "A");
		slots.create("Tetris", "A");
		slots.deleteAll(GAME);
		assertFalse(slots.gameDir(GAME).exists());
		assertTrue(slots.dir("Tetris", "A").isDirectory());
	}

	@Test
	public void whenAGameFolderIsRenamedAllItsSavesAndTheChoiceMoveWithIt() throws IOException {
		write(new File(work, "saves/Snake/d"), "default");
		slots.create(GAME, "A");
		write(new File(slots.dir(GAME, "A"), "x"), "1");
		slots.setActive(GAME, "A");
		slots.moveAll(GAME, "Snake_1");
		assertFalse(slots.gameDir(GAME).exists());
		assertEquals(Collections.singletonList("A"), slots.list("Snake_1"));
		assertEquals("A", slots.active("Snake_1"));
		assertEquals("1", read(new File(slots.dir("Snake_1", "A"), "x")));
		assertEquals("the default slot went with it", "default",
				read(new File(slots.dir("Snake_1", SaveSlots.DEFAULT), "d")));
		slots.moveAll("NoSlots", "Other"); // nothing to move is fine
	}

	@Test
	public void theDisplayLabelOfTheDefaultSlotIsSuppliedByTheCaller() {
		assertEquals("Default", SaveSlots.label(SaveSlots.DEFAULT, "Default"));
		assertEquals("Standard", SaveSlots.label(null, "Standard"));
		assertEquals("Alice", SaveSlots.label("Alice", "Default"));
	}
}
