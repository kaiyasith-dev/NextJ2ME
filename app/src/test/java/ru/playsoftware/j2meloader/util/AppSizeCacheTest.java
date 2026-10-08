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
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;

public class AppSizeCacheTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File app;
	private File data;
	private final AppSizeCache cache = new AppSizeCache();

	private static void write(File f, int bytes) throws IOException {
		//noinspection ResultOfMethodCallIgnored
		f.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(new byte[bytes]);
		}
	}

	@Before
	public void setUp() throws IOException {
		app = tmp.newFolder("converted", "Game");
		data = new File(tmp.getRoot(), "data/Game");
		write(new File(app, "converted.dex"), 1000);
		write(new File(app, "res.jar"), 500);
		assertTrue(app.setLastModified(1_000_000_000_000L));
	}

	@Test
	public void gameFilesAreReadOnceAndThenServedFromTheCache() {
		assertEquals(1500, cache.totalSize("Game", app, data));
		assertEquals(1, cache.fullScans());
		for (int i = 0; i < 5; i++) {
			assertEquals(1500, cache.totalSize("Game", app, data));
		}
		assertEquals("no further reads of the game files", 1, cache.fullScans());
	}

	@Test
	public void savedDataIsAlwaysMeasuredFresh() throws IOException {
		assertEquals(1500, cache.totalSize("Game", app, data));
		write(new File(data, "rms/score"), 40);
		assertEquals(1540, cache.totalSize("Game", app, data));
		write(new File(data, "rms/score"), 90);   // overwritten in place: folder time does not change
		assertEquals(1590, cache.totalSize("Game", app, data));
		assertEquals(1, cache.fullScans());
	}

	@Test
	public void aReinstallChangesTheFolderTimeAndIsMeasuredAgain() throws IOException {
		assertEquals(1500, cache.totalSize("Game", app, data));
		write(new File(app, "extra.bin"), 100);
		assertTrue(app.setLastModified(1_000_000_005_000L));
		assertEquals(1600, cache.totalSize("Game", app, data));
		assertEquals(2, cache.fullScans());
	}

	@Test
	public void gamesAreCachedSeparately() throws IOException {
		File other = tmp.newFolder("converted", "Other");
		write(new File(other, "converted.dex"), 70);
		assertEquals(1500, cache.totalSize("Game", app, data));
		assertEquals(70, cache.totalSize("Other", other, new File(tmp.getRoot(), "data/Other")));
		assertEquals(2, cache.fullScans());
	}

	@Test
	public void gamesThatAreNoLongerListedAreForgotten() {
		cache.totalSize("Game", app, data);
		cache.retainOnly(Collections.<String>emptySet());
		cache.totalSize("Game", app, data);
		assertEquals(2, cache.fullScans());
	}

	@Test
	public void aMissingGameCountsAsZero() {
		assertEquals(0, cache.totalSize("Gone", new File(tmp.getRoot(), "nope"), new File(tmp.getRoot(), "nope2")));
	}
}
