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

import androidx.annotation.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the parsed screen settings of each game and parses a file again only when it changed
 * (its modification time or size), so refreshing the games list stays cheap.
 */
public final class ScreenInfoCache {
	private static final class Entry {
		final String path;
		final long stamp;
		final ScreenInfo info;

		Entry(String path, long stamp, ScreenInfo info) {
			this.path = path;
			this.stamp = stamp;
			this.info = info;
		}
	}

	private final Map<String, Entry> entries = new HashMap<>();
	private int parses;

	/**
	 * The screen settings for {@code key}: from the game's own {@code config}, or from
	 * {@code defaultConfig} (the default profile) while the game has none. Null if neither can be read.
	 */
	@Nullable
	public synchronized ScreenInfo get(String key, File config, @Nullable File defaultConfig) {
		boolean own = config.isFile();
		File used = own ? config : (defaultConfig != null && defaultConfig.isFile() ? defaultConfig : null);
		if (used == null) {
			entries.remove(key);
			return null;
		}
		long stamp = used.lastModified() * 31 + used.length();
		Entry e = entries.get(key);
		if (e == null || !e.path.equals(used.getPath()) || e.stamp != stamp) {
			parses++;
			ScreenInfo info = ScreenInfo.read(used);
			if (info == null) {
				entries.remove(key);
				return null;
			}
			e = new Entry(used.getPath(), stamp, info);
			entries.put(key, e);
		}
		return e.info;
	}

	/** Forgets the games that are no longer listed. */
	public synchronized void retainOnly(Set<String> keys) {
		entries.keySet().retainAll(keys);
	}

	/** How many times a file was really parsed; for tests. */
	synchronized int parses() {
		return parses;
	}
}
