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

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Remembers how big each installed game is, so the list does not read every game's files again
 * each time it is refreshed.
 * <p>
 * The game files do not change after an install, and an install replaces the whole folder, so
 * they are measured once and measured again only when the folder's modification time changes.
 * The saved data is small and does change while the game runs, so it is always measured fresh.
 */
public final class AppSizeCache {
	private static final class Entry {
		final long folderTime;
		final long bytes;

		Entry(long folderTime, long bytes) {
			this.folderTime = folderTime;
			this.bytes = bytes;
		}
	}

	private final Map<String, Entry> entries = new HashMap<>();
	private int fullScans;

	/**
	 * Game files (cached) plus saved data (fresh, every folder given), in bytes. Reads the disk:
	 * not for the UI thread.
	 */
	public synchronized long totalSize(String key, File appDir, File... dataDirs) {
		long time = appDir.lastModified();
		Entry e = entries.get(key);
		if (e == null || e.folderTime != time) {
			e = new Entry(time, StorageSize.sizeOf(appDir));
			entries.put(key, e);
			fullScans++;
		}
		long total = e.bytes;
		for (File dataDir : dataDirs) {
			total += StorageSize.sizeOf(dataDir);
		}
		return total;
	}

	/** Forgets the games that are no longer listed. */
	public synchronized void retainOnly(Set<String> keys) {
		entries.keySet().retainAll(keys);
	}

	/** How many times a game's files were really read; for tests. */
	synchronized int fullScans() {
		return fullScans;
	}
}
