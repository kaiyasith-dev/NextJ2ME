/*
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The candidates of one scan together with the value each had when it was last read.
 * <p>
 * It is the "snapshot" every filter step compares against. Candidates are stored per region
 * in compact parallel arrays ({@code int} slot + {@code long} bits, 12 bytes per candidate) and
 * refer to regions by their stable id only, so a snapshot never keeps game objects alive.
 */
public final class MemorySnapshot {
	/** Candidates of one region. */
	static final class Block {
		final long regionId;
		int count;
		int[] slots;
		long[] bits;
		/** Strings or byte[] values; only allocated for non numeric scans. */
		Object[] objs;

		Block(long regionId, int capacity, boolean objects) {
			this.regionId = regionId;
			this.slots = new int[capacity];
			this.bits = new long[capacity];
			this.objs = objects ? new Object[capacity] : null;
		}

		void add(int slot, long value, Object obj) {
			if (count == slots.length) {
				int n = Math.max(8, count + (count >> 1));
				slots = Arrays.copyOf(slots, n);
				bits = Arrays.copyOf(bits, n);
				if (objs != null) {
					objs = Arrays.copyOf(objs, n);
				}
			}
			slots[count] = slot;
			bits[count] = value;
			if (objs != null) {
				objs[count] = obj;
			}
			count++;
		}
	}

	/** One result row. */
	public static final class Entry {
		public final long regionId;
		public final int slot;
		public final long bits;
		/** String or byte[] for non numeric scans, else null. */
		public final Object obj;

		Entry(long regionId, int slot, long bits, Object obj) {
			this.regionId = regionId;
			this.slot = slot;
			this.bits = bits;
			this.obj = obj;
		}
	}

	private final boolean objectValues;
	private final ArrayList<Block> blocks = new ArrayList<>();
	private long size;

	public MemorySnapshot(boolean objectValues) {
		this.objectValues = objectValues;
	}

	Block newBlock(long regionId, int capacityHint) {
		Block b = new Block(regionId, Math.max(4, Math.min(capacityHint, 1 << 16)), objectValues);
		blocks.add(b);
		return b;
	}

	void added() {
		size++;
	}

	List<Block> blocks() {
		return blocks;
	}

	public long size() {
		return size;
	}

	public boolean hasObjectValues() {
		return objectValues;
	}

	/** A page of results, in discovery order. */
	public List<Entry> page(long offset, int limit) {
		List<Entry> out = new ArrayList<>(Math.min(limit, 1024));
		long skipped = 0;
		for (Block b : blocks) {
			if (skipped + b.count <= offset) {
				skipped += b.count;
				continue;
			}
			for (int i = (int) Math.max(0, offset - skipped); i < b.count && out.size() < limit; i++) {
				out.add(new Entry(b.regionId, b.slots[i], b.bits[i], b.objs == null ? null : b.objs[i]));
			}
			skipped += b.count;
			if (out.size() >= limit) {
				break;
			}
		}
		return out;
	}

}
