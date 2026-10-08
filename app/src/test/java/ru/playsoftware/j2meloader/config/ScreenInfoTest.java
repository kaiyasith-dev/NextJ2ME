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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ScreenInfoTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File config(String name, String json) throws IOException {
		File dir = tmp.newFolder(name);
		File f = new File(dir, "config.json");
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(json.getBytes("UTF-8"));
		}
		return f;
	}

	@Test
	public void readsOnlyTheResolution() throws IOException {
		ScreenInfo s = ScreenInfo.read(config("a", "{\"Version\":3,\"ScreenWidth\":176,\"ScreenHeight\":208,"
				+ "\"ScreenScaleType\":1,\"Orientation\":2,\"Other\":\"x\"}"));
		assertNotNull(s);
		assertEquals("176×208", s.resolution());
	}

	@Test
	public void filesFromOlderVersionsAreReadToo() throws IOException {
		assertEquals("240×320", ScreenInfo.read(config("b", "{\"ScreenWidth\":240,\"ScreenHeight\":320}")).resolution());
	}

	@Test
	public void unreadableOrIncompleteFilesGiveNothing() throws IOException {
		assertNull(ScreenInfo.read(new File(tmp.getRoot(), "missing.json")));
		assertNull(ScreenInfo.read(config("c", "not json at all")));
		assertNull(ScreenInfo.read(config("d", "{}")));
		assertNull(ScreenInfo.read(config("e", "{\"ScreenWidth\":0,\"ScreenHeight\":0}")));
		assertNull(ScreenInfo.read(config("f", "{\"ScreenWidth\":240}")));
	}

	@Test
	public void equalResolutionsAreEqual() throws IOException {
		ScreenInfo a = ScreenInfo.read(config("g", "{\"ScreenWidth\":240,\"ScreenHeight\":320}"));
		ScreenInfo b = ScreenInfo.read(config("h", "{\"ScreenWidth\":240,\"ScreenHeight\":320,\"Orientation\":3}"));
		ScreenInfo c = ScreenInfo.read(config("i", "{\"ScreenWidth\":320,\"ScreenHeight\":240}"));
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		org.junit.Assert.assertNotEquals(a, c);
	}
}
