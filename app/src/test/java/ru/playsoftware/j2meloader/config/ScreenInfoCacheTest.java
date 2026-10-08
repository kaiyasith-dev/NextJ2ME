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

package ru.playsoftware.j2meloader.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;

public class ScreenInfoCacheTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private final ScreenInfoCache cache = new ScreenInfoCache();

	private static String json(int w, int h) {
		return "{\"Version\":3,\"ScreenWidth\":" + w + ",\"ScreenHeight\":" + h + ",\"ScreenScaleType\":1}";
	}

	private File write(String dir, String content, long time) throws IOException {
		File d = new File(tmp.getRoot(), dir);
		//noinspection ResultOfMethodCallIgnored
		d.mkdirs();
		File f = new File(d, "config.json");
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(content.getBytes("UTF-8"));
		}
		assertTrue(f.setLastModified(time));
		return f;
	}

	@Test
	public void aFileIsParsedOnceUntilItChanges() throws IOException {
		File config = write("game", json(240, 320), 1_000_000_000_000L);
		File none = new File(tmp.getRoot(), "nope.json");
		for (int i = 0; i < 4; i++) {
			assertEquals(240, cache.get("game", config, none).width);
		}
		assertEquals(1, cache.parses());

		write("game", json(176, 208), 1_000_000_005_000L);   // the user changed the resolution
		assertEquals(176, cache.get("game", config, none).width);
		assertEquals(2, cache.parses());
	}

	@Test
	public void aGameWithoutSettingsUsesTheDefaultProfileUntilItHasItsOwn() throws IOException {
		File defaults = write("templates/default", json(240, 320), 1_000_000_000_000L);
		File own = new File(tmp.getRoot(), "configs/Game/config.json");
		ScreenInfo s = cache.get("Game", own, defaults);
		assertEquals(240, s.width);

		write("configs/Game", json(128, 160), 1_000_000_000_000L);
		s = cache.get("Game", own, defaults);
		assertEquals(128, s.width);
	}

	@Test
	public void nothingIsReturnedWhenThereIsNoSettingsFile() {
		File missing = new File(tmp.getRoot(), "missing.json");
		assertNull(cache.get("Game", missing, null));
		assertNull(cache.get("Game", missing, new File(tmp.getRoot(), "also-missing.json")));
		assertEquals(0, cache.parses());
	}

	@Test
	public void gamesNoLongerListedAreForgotten() throws IOException {
		File config = write("game", json(240, 320), 1_000_000_000_000L);
		cache.get("game", config, null);
		cache.retainOnly(Collections.<String>emptySet());
		cache.get("game", config, null);
		assertEquals(2, cache.parses());
	}
}
