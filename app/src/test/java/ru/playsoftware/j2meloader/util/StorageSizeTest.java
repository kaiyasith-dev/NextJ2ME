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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

public class StorageSizeTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static void write(File f, int bytes) throws IOException {
		//noinspection ResultOfMethodCallIgnored
		f.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(new byte[bytes]);
		}
	}

	@Test
	public void sizeOfAddsUpNestedFiles() throws IOException {
		File dir = tmp.newFolder("app");
		write(new File(dir, "converted.dex"), 1000);
		write(new File(dir, "res/a.png"), 500);
		write(new File(dir, "res/deep/b.bin"), 24);
		assertEquals(1524, StorageSize.sizeOf(dir));
		assertEquals(500, StorageSize.sizeOf(new File(dir, "res/a.png")));
	}

	@Test
	public void missingOrEmptyFoldersAreZero() throws IOException {
		assertEquals(0, StorageSize.sizeOf(new File(tmp.getRoot(), "nope")));
		assertEquals(0, StorageSize.sizeOf(tmp.newFolder("empty")));
	}

	@Test
	public void kilobytesRoundUpSoSmallFilesNeverShowZero() {
		assertEquals(0, StorageSize.toKb(0));
		assertEquals(1, StorageSize.toKb(1));
		assertEquals(1, StorageSize.toKb(1024));
		assertEquals(2, StorageSize.toKb(1025));
		assertEquals(1234, StorageSize.toKb(1234L * 1024));
	}

	@Test
	public void formatUsesThousandsSeparators() {
		assertEquals("1,234", StorageSize.formatKb(1234L * 1024, Locale.US));
		assertEquals("0", StorageSize.formatKb(0, Locale.US));
		assertEquals("12", StorageSize.formatKb(12 * 1024, Locale.US));
	}
}
