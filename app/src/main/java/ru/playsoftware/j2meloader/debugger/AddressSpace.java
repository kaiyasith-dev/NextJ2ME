/*
 * Copyright 2026 ksdev
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

package ru.playsoftware.j2meloader.debugger;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The virtual, byte-addressable space of the "Raw memory" scope.
 * <p>
 * It is an emulator-level abstraction: every primitive array of the game gets a base address
 * the first time it is seen and keeps it for the whole session. The bytes at an address are
 * the big-endian image of the array elements. It has nothing to do with the host process
 * address space.
 */
public final class AddressSpace {
	private static final long FIRST_BASE = 0x1000;
	private static final long ALIGN = 16;

	private final TreeMap<Long, long[]> byBase = new TreeMap<>();
	private final Map<Long, Long> baseOf = new HashMap<>();
	private long next = FIRST_BASE;

	/** Result of {@link #locate(long)}. */
	public static final class Located {
		public final long regionId;
		public final int offset;

		Located(long regionId, int offset) {
			this.regionId = regionId;
			this.offset = offset;
		}
	}

	public synchronized long baseFor(long regionId, int length) {
		Long base = baseOf.get(regionId);
		if (base != null) {
			return base;
		}
		long b = next;
		next = alignUp(b + Math.max(length, 1) + ALIGN);
		baseOf.put(regionId, b);
		byBase.put(b, new long[]{regionId, length});
		return b;
	}

	/** Maps a virtual address to a region and offset, or null if nothing is mapped there. */
	public synchronized Located locate(long address) {
		Map.Entry<Long, long[]> e = byBase.floorEntry(address);
		if (e == null) {
			return null;
		}
		long off = address - e.getKey();
		long[] info = e.getValue();
		if (off < 0 || off >= info[1]) {
			return null;
		}
		return new Located(info[0], (int) off);
	}

	public synchronized void clear() {
		byBase.clear();
		baseOf.clear();
		next = FIRST_BASE;
	}

	private static long alignUp(long v) {
		return (v + ALIGN - 1) & ~(ALIGN - 1);
	}

	public static String format(long address) {
		return String.format("0x%08X", address);
	}

	/** Parses {@code 0x1000}, {@code 1000h} or plain hex; throws NumberFormatException. */
	public static long parse(String text) {
		String s = text.trim();
		if (s.startsWith("0x") || s.startsWith("0X")) {
			s = s.substring(2);
		} else if (s.endsWith("h") || s.endsWith("H")) {
			s = s.substring(0, s.length() - 1);
		}
		if (s.isEmpty() || s.length() > 15) {
			throw new NumberFormatException("Bad address: " + text);
		}
		return Long.parseLong(s, 16);
	}
}
