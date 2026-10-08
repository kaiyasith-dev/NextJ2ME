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
import java.text.NumberFormat;
import java.util.Locale;

/** Sizes of an app's files, for showing in KB. */
public final class StorageSize {
	private static final int MAX_DEPTH = 32;

	private StorageSize() {
	}

	/** Total size in bytes of the files under {@code f}; 0 if it does not exist. */
	public static long sizeOf(File f) {
		return sizeOf(f, 0);
	}

	private static long sizeOf(File f, int depth) {
		if (depth > MAX_DEPTH) {
			return 0;
		}
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			long total = 0;
			if (children != null) {
				for (File child : children) {
					total += sizeOf(child, depth + 1);
				}
			}
			return total;
		}
		return f.isFile() ? f.length() : 0;
	}

	/** Whole kilobytes, rounded up, so a non-empty file never shows as 0. */
	public static long toKb(long bytes) {
		return (bytes + 1023) / 1024;
	}

	/** The kilobyte count with thousands separators, e.g. "1,234". */
	public static String formatKb(long bytes, Locale locale) {
		return NumberFormat.getIntegerInstance(locale).format(toKb(bytes));
	}
}
