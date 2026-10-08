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
import java.util.List;

public class ReplaceDirectoryTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File apps;

	@Before
	public void setUp() throws IOException {
		apps = tmp.newFolder("converted");
	}

	private File game(String name, String content) throws IOException {
		File dir = new File(apps, name);
		assertTrue(dir.mkdirs());
		try (FileOutputStream out = new FileOutputStream(new File(dir, "converted.dex"))) {
			out.write(content.getBytes("UTF-8"));
		}
		return dir;
	}

	private static String dex(File dir) throws IOException {
		byte[] buf = new byte[64];
		try (FileInputStream in = new FileInputStream(new File(dir, "converted.dex"))) {
			int n = in.read(buf);
			return new String(buf, 0, n, "UTF-8");
		}
	}

	private List<String> listing() {
		String[] names = apps.list();
		Arrays.sort(names);
		return Arrays.asList(names);
	}

	@Test
	public void theNewCopyReplacesTheOldOneAndNothingIsLeftBehind() throws IOException {
		File target = game("Game", "old");
		File fresh = game(".tmp", "new");
		FileUtils.replaceDirectory(target, fresh);
		assertEquals("new", dex(target));
		assertEquals(Arrays.asList("Game"), listing());
	}

	@Test
	public void aNewGameSimplyMovesIn() throws IOException {
		File fresh = game(".tmp", "new");
		File target = new File(apps, "Game");
		FileUtils.replaceDirectory(target, fresh);
		assertEquals("new", dex(target));
		assertEquals(Arrays.asList("Game"), listing());
	}

	@Test
	public void theOldCopyIsKeptWhenTheNewOneCanNotBeMovedIn() throws IOException {
		File target = game("Game", "old");
		File missing = new File(apps, ".tmp"); // does not exist, so the rename fails
		try {
			FileUtils.replaceDirectory(target, missing);
			fail("expected IOException");
		} catch (IOException expected) {
			// ok
		}
		assertEquals("the game is still there", "old", dex(target));
		assertEquals(Arrays.asList("Game"), listing());
	}

	@Test
	public void aLeftoverFromAnEarlierAttemptDoesNotBlockTheNextOne() throws IOException {
		game(".old-Game", "stale");
		File target = game("Game", "old");
		File fresh = game(".tmp", "new");
		FileUtils.replaceDirectory(target, fresh);
		assertEquals("new", dex(target));
		assertEquals(Arrays.asList("Game"), listing());
	}

	@Test
	public void recoveryPutsTheOldGameBackWhenTheReplacementNeverArrived() throws IOException {
		// the app was killed after the old folder was moved aside
		game(".old-Game", "old");
		FileUtils.recoverReplaced(apps);
		assertEquals("old", dex(new File(apps, "Game")));
		assertEquals(Arrays.asList("Game"), listing());
	}

	@Test
	public void recoveryDropsTheLeftoverWhenTheNewGameIsInPlace() throws IOException {
		game(".old-Game", "old");
		game("Game", "new");
		FileUtils.recoverReplaced(apps);
		assertEquals("new", dex(new File(apps, "Game")));
		assertFalse(new File(apps, ".old-Game").exists());
		assertEquals(Arrays.asList("Game"), listing());
	}

	@Test
	public void recoveryIgnoresOtherFolders() throws IOException {
		game("Game", "keep");
		game(".tmp", "other");
		FileUtils.recoverReplaced(apps);
		assertEquals(Arrays.asList(".tmp", "Game"), listing());
	}
}
