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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class BackupRoundTripTest {
	private static final int VERSION = 5;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File root;

	@Before
	public void setUp() throws IOException {
		root = tmp.newFolder("emulator");
		put("games/Game/saves/rms/1.rms", "save one");
		put("games/Game/saves/rms/2.rms", "save two");
		put("games/Game/config/config.json", "{\"x\":1}");
		put("templates/default/config.json", "{}");
		put("fs/e/photo.jpg", "jpeg");
		put("games/Game/debugger/memory.json", "{}");
		put("games/Game/app/converted.dex", "dex bytes");
		put("games/Game/.tmp/half", "unfinished install");
		put("shaders/custom.glsl", "void main(){}");
		put("cache/junk.bin", "cache");
		put("J2ME-apps.db", "sqlite");
		put("log.txt", "log");
		put(".nomedia", "");
	}

	private void put(String path, String text) throws IOException {
		File f = new File(root, path);
		//noinspection ResultOfMethodCallIgnored
		f.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(text.getBytes("UTF-8"));
		}
	}

	private static String read(File f) throws IOException {
		java.io.FileInputStream in = new java.io.FileInputStream(f);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] b = new byte[256];
			int n;
			while ((n = in.read(b)) > 0) {
				out.write(b, 0, n);
			}
			return out.toString("UTF-8");
		} finally {
			in.close();
		}
	}

	private byte[] backup(BackupScope scope) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BackupWriter.write(root, out, scope, new BackupInfo(VERSION, scope, 1234L, "test"), new BackupProgress());
		return out.toByteArray();
	}

	private static List<String> entries(byte[] zip) throws IOException {
		List<String> names = new ArrayList<>();
		ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
		ZipEntry e;
		while ((e = in.getNextEntry()) != null) {
			names.add(e.getName());
		}
		return names;
	}

	@Test
	public void savesScopeHoldsSavesAndSettingsOnly() throws IOException {
		List<String> names = entries(backup(BackupScope.SAVES));
		assertEquals(BackupWriter.INFO_ENTRY, names.get(0));
		assertTrue(names.contains("games/Game/saves/rms/1.rms"));
		assertTrue(names.contains("games/Game/config/config.json"));
		assertTrue(names.contains("templates/default/config.json"));
		assertTrue(names.contains("fs/e/photo.jpg"));
		assertTrue(names.contains("games/Game/debugger/memory.json"));
		assertFalse("games are not in a saves backup", names.contains("games/Game/app/converted.dex"));
		assertFalse(names.contains("shaders/custom.glsl"));
	}

	@Test
	public void allScopeAddsGamesButNeverCacheDatabaseOrUnfinishedInstalls() throws IOException {
		List<String> names = entries(backup(BackupScope.ALL));
		assertTrue(names.contains("games/Game/app/converted.dex"));
		assertTrue(names.contains("shaders/custom.glsl"));
		assertTrue(names.contains("games/Game/saves/rms/1.rms"));
		for (String n : names) {
			assertFalse(n, n.startsWith("cache/"));
			assertFalse(n, n.startsWith("converted/.tmp"));
			assertFalse(n, n.contains("J2ME-apps.db"));
			assertFalse(n, n.equals("log.txt"));
			assertFalse(n, n.endsWith(".nomedia"));
		}
	}

	@Test
	public void restoreRecreatesFilesAndOverwritesChangedOnes() throws IOException {
		byte[] zip = backup(BackupScope.ALL);
		File target = tmp.newFolder("fresh");
		BackupProgress progress = new BackupProgress();
		BackupReader.Result r = BackupReader.restore(new ByteArrayInputStream(zip), target, VERSION, progress);
		assertEquals(8, r.files);
		assertEquals(0, r.skipped);
		assertEquals(r.files, progress.files());
		assertEquals("save one", read(new File(target, "games/Game/saves/rms/1.rms")));
		assertEquals("dex bytes", read(new File(target, "games/Game/app/converted.dex")));
		assertEquals(BackupScope.ALL, r.info.scope());
		assertEquals(1234L, r.info.created());

		// a changed file in the target is replaced, and no temp files are left
		put("games/Game/saves/rms/1.rms", "newer progress");
		File again = new File(target, "games/Game/saves/rms/1.rms");
		try (FileOutputStream out = new FileOutputStream(again)) {
			out.write("local edit".getBytes("UTF-8"));
		}
		BackupReader.restore(new ByteArrayInputStream(zip), target, VERSION, new BackupProgress());
		assertEquals("save one", read(again));
		for (String n : new File(target, "games/Game/saves/rms").list()) {
			assertFalse(n, n.endsWith(".restore-tmp"));
		}
	}

	@Test
	public void saveSlotsAndTheChosenSlotAreBackedUpAndRestored() throws IOException {
		put("games/Game/saves/slots/Alice/score", "slot data");
		put("games/Game/saves/.active", "Alice");
		byte[] zip = backup(BackupScope.SAVES);
		List<String> names = entries(zip);
		assertTrue(names.contains("games/Game/saves/slots/Alice/score"));
		assertTrue(names.contains("games/Game/saves/.active"));
		File target = tmp.newFolder("slotsTarget");
		BackupReader.restore(new ByteArrayInputStream(zip), target, VERSION, new BackupProgress());
		assertEquals("slot data", read(new File(target, "games/Game/saves/slots/Alice/score")));
		assertEquals("Alice", read(new File(target, "games/Game/saves/.active")));
	}

	@Test
	public void infoCanBeReadWithoutRestoring() throws IOException {
		BackupInfo info = BackupReader.readInfo(new ByteArrayInputStream(backup(BackupScope.SAVES)));
		assertEquals(VERSION, info.dataVersion());
		assertEquals(BackupScope.SAVES, info.scope());
		assertEquals("test", info.appVersion());
	}

	@Test
	public void aDifferentDataVersionIsRefusedBeforeWritingAnything() throws IOException {
		byte[] zip = backup(BackupScope.SAVES);
		File target = tmp.newFolder("other");
		try {
			BackupReader.restore(new ByteArrayInputStream(zip), target, VERSION + 1, new BackupProgress());
			fail("expected BackupException");
		} catch (BackupException expected) {
			assertTrue(expected.getMessage().contains("different data layout"));
		}
		assertEquals(0, target.list().length);
	}

	private static byte[] zipOf(String[] names) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZipOutputStream zip = new ZipOutputStream(out);
		for (String n : names) {
			zip.putNextEntry(new ZipEntry(n));
			zip.write("x".getBytes("UTF-8"));
			zip.closeEntry();
		}
		zip.close();
		return out.toByteArray();
	}

	@Test
	public void filesThatAreNotBackupsAreRejected() throws IOException {
		byte[][] notBackups = {
				new byte[0],
				"just some text".getBytes("UTF-8"),
				zipOf(new String[]{"games/Game/saves/rms/1.rms"}),
				zipOf(new String[]{BackupWriter.INFO_ENTRY}),   // "x" is not valid info JSON
		};
		for (byte[] bytes : notBackups) {
			try {
				BackupReader.restore(new ByteArrayInputStream(bytes), tmp.newFolder(), VERSION, new BackupProgress());
				fail("expected BackupException");
			} catch (BackupException expected) {
				// ok
			}
		}
	}

	/** A backup whose second entry has the given name, behind a valid header. */
	private byte[] withEntry(String name) throws IOException {
		ByteArrayOutputStream header = new ByteArrayOutputStream();
		BackupWriter.write(tmp.newFolder(), header, BackupScope.SAVES,
				new BackupInfo(VERSION, BackupScope.SAVES, 1L, "t"), new BackupProgress());
		// re-zip: header entry first, then the hostile one
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZipOutputStream zip = new ZipOutputStream(out);
		zip.putNextEntry(new ZipEntry(BackupWriter.INFO_ENTRY));
		zip.write(("{\"type\":\"nextj2me-backup\",\"format\":1,\"dataVersion\":" + VERSION + "}").getBytes("UTF-8"));
		zip.closeEntry();
		zip.putNextEntry(new ZipEntry(name));
		zip.write("evil".getBytes("UTF-8"));
		zip.closeEntry();
		zip.close();
		return out.toByteArray();
	}

	@Test
	public void pathTraversalAndAbsolutePathsAreRefused() throws IOException {
		for (String name : Arrays.asList("../escaped.txt", "data/../../escaped.txt", "/etc/escaped.txt",
				"data\\..\\escaped.txt", "data//x", "data/./x")) {
			File target = tmp.newFolder();
			try {
				BackupReader.restore(new ByteArrayInputStream(withEntry(name)), target, VERSION, new BackupProgress());
				fail("expected BackupException for " + name);
			} catch (BackupException expected) {
				// ok
			}
			assertFalse(new File(target.getParentFile(), "escaped.txt").exists());
			assertEquals(0, target.list().length);
		}
	}

	@Test
	public void unknownTopLevelFoldersAreSkippedNotWritten() throws IOException {
		File target = tmp.newFolder("skip");
		BackupReader.Result r = BackupReader.restore(
				new ByteArrayInputStream(withEntry("evil/payload.bin")), target, VERSION, new BackupProgress());
		assertEquals(0, r.files);
		assertEquals(1, r.skipped);
		assertEquals(0, target.list().length);
	}

	@Test
	public void aFolderInTheWayIsReportedNotOverwritten() throws IOException {
		File target = tmp.newFolder("clash");
		assertTrue(new File(target, "games/Game/saves").mkdirs());
		try {
			BackupReader.restore(new ByteArrayInputStream(withEntry("games/Game/saves")), target, VERSION, new BackupProgress());
			fail("expected BackupException");
		} catch (BackupException expected) {
			assertTrue(new File(target, "games/Game/saves").isDirectory());
		}
	}

	@Test
	public void cancellingStopsBackupAndRestore() throws IOException {
		BackupProgress cancelled = new BackupProgress();
		cancelled.cancel();
		try {
			BackupWriter.write(root, new ByteArrayOutputStream(), BackupScope.ALL,
					new BackupInfo(VERSION, BackupScope.ALL, 1L, "t"), cancelled);
			fail("expected cancel");
		} catch (BackupProgress.Cancelled expected) {
			// ok
		}
		File target = tmp.newFolder("cancel");
		try {
			BackupReader.restore(new ByteArrayInputStream(backup(BackupScope.ALL)), target, VERSION, cancelled);
			fail("expected cancel");
		} catch (BackupProgress.Cancelled expected) {
			// ok
		}
		assertEquals("nothing written after cancelling", 0, target.list().length);
	}

	@Test
	public void emptyWorkFolderGivesAValidEmptyBackup() throws IOException {
		root = tmp.newFolder("empty");
		byte[] zip = backup(BackupScope.ALL);
		assertEquals(Arrays.asList(BackupWriter.INFO_ENTRY), entries(zip));
		File target = tmp.newFolder("emptyTarget");
		BackupReader.Result r = BackupReader.restore(new ByteArrayInputStream(zip), target, VERSION, new BackupProgress());
		assertEquals(0, r.files);
	}

	@Test
	public void everyGamesFoldersAreIncludedAndOnlyTheKnownParts() throws IOException {
		put("games/Other/saves/rms/1.rms", "other save");
		put("games/Other/config/config.json", "{}");
		put("games/Other/app/converted.dex", "other dex");
		put("games/Other/notes.txt", "not part of any backup");
		put("games/Game/evil/x", "unknown folder of a game");
		List<String> saves = entries(backup(BackupScope.SAVES));
		assertTrue(saves.contains("games/Game/saves/rms/1.rms"));
		assertTrue(saves.contains("games/Other/saves/rms/1.rms"));
		assertTrue(saves.contains("games/Other/config/config.json"));
		assertFalse(saves.contains("games/Other/app/converted.dex"));
		List<String> all = entries(backup(BackupScope.ALL));
		assertTrue(all.contains("games/Other/app/converted.dex"));
		for (String n : all) {
			assertFalse(n, n.contains("notes.txt"));
			assertFalse(n, n.startsWith("games/Game/evil"));
		}
	}

	@Test
	public void unfinishedAndLeftoverFoldersOfGamesNeverGoIntoABackup() throws IOException {
		put("games/Game/.old-app/converted.dex", "left over from a cut short reinstall");
		put("games/Game/app/.tmp/x", "unfinished");
		put("games/.tmp/y", "the temporary folder of an install");
		put("games/.hidden/saves/z", "a hidden game folder");
		for (BackupScope scope : BackupScope.values()) {
			for (String n : entries(backup(scope))) {
				assertFalse(n, n.contains(".old-app"));
				assertFalse(n, n.contains("/.tmp/") || n.startsWith("games/.tmp"));
				assertFalse(n, n.startsWith("games/.hidden"));
			}
		}
	}

	@Test
	public void aRestoreOnlyWritesTheKnownPartsOfAGame() throws IOException {
		for (String name : new String[]{"games/Game/evil/x", "games/Game", "games/.hidden/saves/x",
				"games/Game/notes.txt", "games"}) {
			File target = tmp.newFolder();
			BackupReader.Result r = BackupReader.restore(
					new ByteArrayInputStream(withEntry(name)), target, VERSION, new BackupProgress());
			assertEquals(name, 0, r.files);
			assertEquals(name, 1, r.skipped);
			assertEquals(name, 0, target.list().length);
		}
		File target = tmp.newFolder();
		BackupReader.Result r = BackupReader.restore(
				new ByteArrayInputStream(withEntry("games/Game/config/config.json")), target, VERSION, new BackupProgress());
		assertEquals(1, r.files);
		assertEquals("evil", read(new File(target, "games/Game/config/config.json")));
	}

	@Test
	public void aBackupOfTheOldLayoutIsRefusedAsAnotherDataVersion() throws IOException {
		byte[] zip = backup(BackupScope.SAVES);
		try {
			BackupReader.restore(new ByteArrayInputStream(zip), tmp.newFolder(), VERSION - 1, new BackupProgress());
			fail("expected BackupException");
		} catch (BackupException expected) {
			assertTrue(expected.getMessage().contains("different data layout"));
		}
	}
}
