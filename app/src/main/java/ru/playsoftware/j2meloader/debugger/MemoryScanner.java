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

package ru.playsoftware.j2meloader.debugger;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Snapshot based scanner. It talks to {@link MemoryProvider}s only.
 * <ul>
 * <li>The first scan enumerates the provider's regions once and records the matching slots
 * (or every slot, for "unknown initial value").</li>
 * <li>Every next scan re-reads <em>only the remembered candidates</em>, found again through
 * their stable region ids, and keeps those that satisfy the chosen comparison. Nothing is
 * enumerated again, so filtering is cheap even for huge first scans.</li>
 * </ul>
 * All methods are synchronous and meant to run on a background thread; they poll the
 * {@link CancelToken} and throw {@link CancellationException} when cancelled, leaving the
 * session untouched.
 */
public final class MemoryScanner {
	/** Receives coarse progress; may be called from the scanning thread at any time. */
	public interface Progress {
		void onProgress(long regionsDone, long candidates);
	}

	private static final Progress NO_PROGRESS = new Progress() {
		@Override
		public void onProgress(long regionsDone, long candidates) {
		}
	};

	/** A group scan gives up on a region where one value matches this often (it would need huge lists). */
	static final int GROUP_MATCH_CAP = 200_000;
	private static final int CHUNK = 8192;
	private static final int BYTE_CHUNK = 1 << 16;
	private static final long PROGRESS_INTERVAL_MS = 80;

	private final Map<ScanScope, MemoryProvider> providers = new EnumMap<>(ScanScope.class);

	public MemoryScanner(MemoryProvider... all) {
		for (MemoryProvider p : all) {
			providers.put(p.scope(), p);
		}
	}

	public MemoryProvider provider(ScanScope scope) {
		MemoryProvider p = providers.get(scope);
		if (p == null) {
			throw new IllegalArgumentException("No provider for " + scope);
		}
		return p;
	}

	// ------------------------------------------------------------------ first scan

	public void firstScan(ScanSession session, ScanParams p, Progress progress, CancelToken cancel) {
		long t0 = System.currentTimeMillis();
		MemoryValue target = p.validate(true);
		MemoryProvider prov = provider(p.scope);
		Collector c = new Collector(p, target, progress == null ? NO_PROGRESS : progress, cancel);
		prov.enumerate(c, cancel);
		if (cancel.isCancelled()) {
			throw new CancellationException("Scan cancelled");
		}
		long ms = System.currentTimeMillis() - t0;
		c.snapshot.seal();
		if (c.groupSkipped > 0) {
			session.setWarning(c.groupSkipped + (c.groupSkipped == 1 ? " region was" : " regions were")
					+ " skipped: a value matched more than " + GROUP_MATCH_CAP + " times there. "
					+ "Use rarer values or a different scope.");
		}
		session.update(c.snapshot, c.truncated,
				session.step(p.mode, p.value, c.snapshot.size(), ms, c.truncated), c.matchLength);
	}

	/**
	 * First scan of an "any integer size" scan. The sessions each have one integer type; they are
	 * all filled during a single walk over the provider's regions, so it costs little more than a
	 * normal scan. The candidate limit is shared between them.
	 */
	public void firstScanFuzzy(List<ScanSession> sessions, ScanParams p, Progress progress, CancelToken cancel) {
		long t0 = System.currentTimeMillis();
		final int n = sessions.size();
		MemoryProvider prov = provider(p.scope);
		final Progress prog = progress == null ? NO_PROGRESS : progress;
		final Collector[] collectors = new Collector[n];
		for (int i = 0; i < n; i++) {
			ScanParams pt = p.copy();
			pt.fuzzy = false;
			pt.group = false;
			pt.type = sessions.get(i).type;
			pt.maxCandidates = Math.max(1, p.maxCandidates / n);
			collectors[i] = new Collector(pt, pt.validate(true), NO_PROGRESS, cancel);
		}
		final long[] regions = {0};
		final long[] lastReport = {0};
		prov.enumerate(new VmInspector.RegionSink() {
			@Override
			public boolean accept(MemoryRegion region) {
				boolean any = false;
				for (Collector c : collectors) {
					if (c.accept(region)) {
						any = true;
					}
				}
				regions[0]++;
				long now = System.currentTimeMillis();
				if (now - lastReport[0] > PROGRESS_INTERVAL_MS) {
					lastReport[0] = now;
					long total = 0;
					for (Collector c : collectors) {
						total += c.snapshot.size();
					}
					prog.onProgress(regions[0], total);
				}
				return any && !cancel.isCancelled();
			}
		}, cancel);
		if (cancel.isCancelled()) {
			throw new CancellationException("Scan cancelled");
		}
		long ms = System.currentTimeMillis() - t0;
		for (int i = 0; i < n; i++) {
			Collector c = collectors[i];
			c.snapshot.seal();
			sessions.get(i).update(c.snapshot, c.truncated,
					sessions.get(i).step(p.mode, p.value, c.snapshot.size(), ms, c.truncated), c.matchLength);
		}
	}

	private final class Collector implements VmInspector.RegionSink {
		final ScanParams p;
		final ScanMode mode;
		final ValueType type;
		final boolean be;
		final long targetBits;
		final byte[] needle;
		final String text;
		/** Values of a group scan, or null for a normal scan. */
		final long[] groupBits;
		final Progress progress;
		final CancelToken cancel;
		final MemorySnapshot snapshot;
		final long[] buf = new long[CHUNK];
		byte[] byteBuf;
		boolean truncated;
		int matchLength;
		long regions;
		long lastReport;
		/** Group scan: regions skipped because a value matched too often, and the current region's state. */
		int groupSkipped;
		boolean groupOverflow;

		Collector(ScanParams p, MemoryValue target, Progress progress, CancelToken cancel) {
			this.p = p;
			this.mode = p.mode;
			this.type = p.type;
			this.be = p.bigEndian;
			this.progress = progress;
			this.cancel = cancel;
			this.snapshot = new MemorySnapshot(!type.isNumeric());
			this.targetBits = target != null && type.isNumeric() ? target.bits : 0;
			this.needle = target != null && !type.isNumeric() ? target.rawBytes() : null;
			this.text = target != null && type == ValueType.STRING ? target.text : null;
			this.groupBits = p.group ? p.parseGroup() : null;
			if (needle != null && p.scope == ScanScope.RAW) {
				matchLength = needle.length;
			}
		}

		@Override
		public boolean accept(MemoryRegion r) {
			if (cancel.isCancelled() || truncated) {
				return false;
			}
			try {
				scanRegion(r);
			} catch (RuntimeException e) {
				// the region changed or vanished under us: skip it, the scan goes on
			}
			regions++;
			long now = System.currentTimeMillis();
			if (now - lastReport > PROGRESS_INTERVAL_MS) {
				lastReport = now;
				progress.onProgress(regions, snapshot.size());
			}
			return !truncated && !cancel.isCancelled();
		}

		private void scanRegion(MemoryRegion r) {
			if (groupBits != null) {
				groupRegion(r);
			} else if (type.isNumeric()) {
				if (r.isByteAddressable()) {
					rawNumeric(r);
				} else {
					typedNumeric(r);
				}
			} else if (type == ValueType.STRING && !r.isByteAddressable()) {
				stringFields(r);
			} else if (r.isByteAddressable()) {
				rawBytes(r);
			}
		}

		private MemorySnapshot.Block block(MemorySnapshot.Block b, MemoryRegion r, int hint) {
			return b != null ? b : snapshot.newBlock(r.id(), hint);
		}

		/** @return false once the candidate limit is reached */
		private boolean add(MemorySnapshot.Block b, int slot, long bits, Object obj) {
			b.add(slot, bits, obj);
			snapshot.added();
			if (snapshot.size() >= p.maxCandidates) {
				truncated = true;
				return false;
			}
			return true;
		}

		private void typedNumeric(MemoryRegion r) {
			int[] slots = r.slotsAccepting(type);
			MemorySnapshot.Block b = null;
			if (slots == null) {
				int n = r.slotCount();
				int hint = mode == ScanMode.UNKNOWN ? n : 8;
				for (int start = 0; start < n; start += CHUNK) {
					if (cancel.isCancelled()) {
						return;
					}
					int cnt = Math.min(CHUNK, n - start);
					r.readBulk(type, be, start, cnt, 1, buf);
					for (int k = 0; k < cnt; k++) {
						if (mode.matches(type, buf[k], 0, targetBits)) {
							b = block(b, r, hint);
							if (!add(b, start + k, buf[k], null)) {
								return;
							}
						}
					}
				}
			} else {
				for (int slot : slots) {
					long v = r.readRaw(slot, type, be);
					if (mode.matches(type, v, 0, targetBits)) {
						b = block(b, r, slots.length);
						if (!add(b, slot, v, null)) {
							return;
						}
					}
				}
			}
		}

		private void rawNumeric(MemoryRegion r) {
			int w = type.width();
			int step = p.rawStep();
			int size = r.slotCount();
			if (size < w) {
				return;
			}
			int last = size - w;
			int hint = mode == ScanMode.UNKNOWN ? last / step + 1 : 8;
			MemorySnapshot.Block b = null;
			for (int start = 0; start <= last; start += CHUNK * step) {
				if (cancel.isCancelled()) {
					return;
				}
				int cnt = Math.min(CHUNK, (last - start) / step + 1);
				r.readBulk(type, be, start, cnt, step, buf);
				for (int k = 0; k < cnt; k++) {
					if (mode.matches(type, buf[k], 0, targetBits)) {
						b = block(b, r, hint);
						if (!add(b, start + k * step, buf[k], null)) {
							return;
						}
					}
				}
			}
		}

		private void collect(GroupMatcher.IntList[] lists, int pos, long bits) {
			if (groupOverflow) {
				return;
			}
			for (int j = 0; j < groupBits.length; j++) {
				if (type.valueEquals(bits, groupBits[j])) {
					if (lists[j].size >= GROUP_MATCH_CAP) {
						groupOverflow = true; // too common here: stop collecting, the region is skipped
						return;
					}
					lists[j].add(pos);
				}
			}
		}

		/**
		 * Group scan of one region: notes where each value occurs, then keeps the positions of
		 * every group of values that sit close together.
		 */
		private void groupRegion(MemoryRegion r) {
			boolean raw = r.isByteAddressable();
			int step = raw ? p.rawStep() : 1;
			groupOverflow = false;
			GroupMatcher.IntList[] lists = new GroupMatcher.IntList[groupBits.length];
			for (int j = 0; j < lists.length; j++) {
				lists[j] = new GroupMatcher.IntList();
			}
			int[] slots = null;
			if (raw) {
				int w = type.width();
				int size = r.slotCount();
				if (size < w) {
					return;
				}
				int last = size - w;
				for (int start = 0; start <= last; start += CHUNK * step) {
					if (cancel.isCancelled()) {
						return;
					}
					int cnt = Math.min(CHUNK, (last - start) / step + 1);
					r.readBulk(type, be, start, cnt, step, buf);
					int base = start / step;
					for (int k = 0; k < cnt; k++) {
						collect(lists, base + k, buf[k]);
					}
				}
			} else {
				slots = r.slotsAccepting(type);
				if (slots == null) {
					int n = r.slotCount();
					for (int start = 0; start < n; start += CHUNK) {
						if (cancel.isCancelled()) {
							return;
						}
						int cnt = Math.min(CHUNK, n - start);
						r.readBulk(type, be, start, cnt, 1, buf);
						for (int k = 0; k < cnt; k++) {
							collect(lists, start + k, buf[k]);
						}
					}
				} else {
					for (int idx = 0; idx < slots.length; idx++) {
						collect(lists, idx, r.readRaw(slots[idx], type, be));
					}
				}
			}
			if (groupOverflow) {
				groupSkipped++;
				return;
			}
			// the fields of one object or class belong together by definition: no window there
			boolean fields = !raw && r.kind() != MemoryRegion.Kind.ARRAY;
			int window = fields ? Integer.MAX_VALUE : p.groupWindow;
			GroupMatcher.IntList hits = GroupMatcher.match(lists, window, p.groupOrdered && !fields);
			if (hits.size == 0) {
				return;
			}
			int[] positions = hits.sortedUnique();
			MemorySnapshot.Block b = null;
			for (int pos : positions) {
				int slot = raw ? pos * step : slots != null ? slots[pos] : pos;
				long value = r.readRaw(slot, type, be);
				b = block(b, r, positions.length);
				if (!add(b, slot, value, null)) {
					return;
				}
			}
		}

		private void stringFields(MemoryRegion r) {
			int[] slots = r.slotsAccepting(ValueType.STRING);
			MemorySnapshot.Block b = null;
			for (int slot : slots) {
				String s = r.readString(slot);
				if (matchesString(mode, s, null, text)) {
					b = block(b, r, slots.length);
					if (!add(b, slot, 0, s)) {
						return;
					}
				}
			}
		}

		private void rawBytes(MemoryRegion r) {
			int len = needle.length;
			int size = r.imageSize();
			if (len == 0 || size < len) {
				return;
			}
			int align = Math.max(1, p.alignment);
			if (byteBuf == null) {
				byteBuf = new byte[BYTE_CHUNK + len];
			} else if (byteBuf.length < BYTE_CHUNK + len) {
				byteBuf = new byte[BYTE_CHUNK + len];
			}
			byte first = needle[0];
			MemorySnapshot.Block b = null;
			for (int off = 0; off + len <= size; off += BYTE_CHUNK) {
				if (cancel.isCancelled()) {
					return;
				}
				int n = Math.min(BYTE_CHUNK + len - 1, size - off);
				r.readImage(off, byteBuf, 0, n);
				int limit = Math.min(BYTE_CHUNK, n - len + 1);
				for (int i = 0; i < limit; i++) {
					if (byteBuf[i] != first || ((off + i) % align) != 0) {
						continue;
					}
					int j = 1;
					while (j < len && byteBuf[i + j] == needle[j]) {
						j++;
					}
					if (j == len) {
						b = block(b, r, 8);
						if (!add(b, off + i, 0, needle)) {
							return;
						}
					}
				}
			}
		}
	}

	private static boolean matchesString(ScanMode mode, String cur, String prev, String text) {
		switch (mode) {
			case UNKNOWN:
				return true;
			case EXACT:
				return cur != null && cur.contains(text);
			case EQUAL_TO:
				return cur != null && cur.equals(text);
			case NOT_EQUAL_TO:
				return cur != null && !cur.equals(text);
			case CHANGED:
				return cur == null ? prev != null : !cur.equals(prev);
			case UNCHANGED:
				return cur == null ? prev == null : cur.equals(prev);
			default:
				return false;
		}
	}

	// ------------------------------------------------------------------ next scan

	/**
	 * Filters the session's candidates. Scope, type, byte order and encoding are taken from the
	 * session; only mode and value come from {@code p}.
	 */
	public void nextScan(ScanSession session, ScanParams p, Progress progress, CancelToken cancel) {
		long t0 = System.currentTimeMillis();
		ScanParams q = p.copy();
		q.scope = session.scope;
		q.type = session.type;
		q.bigEndian = session.bigEndian;
		q.alignment = session.alignment;
		q.encoding = session.encoding;
		MemoryValue target = q.validate(false);
		MemorySnapshot old = session.snapshot();
		if (old == null || old.size() == 0) {
			throw new IllegalStateException("There is nothing to filter. Start a new scan.");
		}
		Progress prog = progress == null ? NO_PROGRESS : progress;
		MemoryProvider prov = provider(session.scope);
		ValueType type = q.type;
		ScanMode mode = q.mode;
		boolean be = q.bigEndian;
		boolean numeric = type.isNumeric();
		long targetBits = target != null && numeric ? target.bits : 0;
		byte[] needle = target != null && !numeric ? target.rawBytes() : null;
		String text = target != null && type == ValueType.STRING ? target.text : null;
		int matchLength = session.matchLength();
		if (needle != null && session.scope == ScanScope.RAW) {
			matchLength = needle.length;
		}

		MemorySnapshot neu = new MemorySnapshot(old.hasObjectValues());
		long done = 0;
		long lastReport = 0;
		for (MemorySnapshot.Block ob : old.blocks()) {
			if (cancel.isCancelled()) {
				throw new CancellationException("Scan cancelled");
			}
			MemoryRegion r = prov.resolve(ob.regionId);
			if (r == null || !r.isAvailable()) {
				continue; // the object is gone: its candidates are dropped
			}
			MemorySnapshot.Block nb = null;
			for (int i = 0; i < ob.count; i++) {
				int slot = ob.slots[i];
				try {
					if (numeric) {
						long cur = r.readRaw(slot, type, be);
						if (mode.matches(type, cur, ob.bits[i], targetBits)) {
							if (nb == null) {
								nb = neu.newBlock(ob.regionId, ob.count);
							}
							nb.add(slot, cur, null);
							neu.added();
						}
					} else if (type == ValueType.STRING && !r.isByteAddressable()) {
						String cur = r.readString(slot);
						if (matchesString(mode, cur, (String) ob.objs[i], text)) {
							if (nb == null) {
								nb = neu.newBlock(ob.regionId, ob.count);
							}
							nb.add(slot, 0, cur);
							neu.added();
						}
					} else {
						byte[] prev = (byte[]) ob.objs[i];
						int len = (mode == ScanMode.CHANGED || mode == ScanMode.UNCHANGED || needle == null)
								? prev.length : needle.length;
						byte[] cur = r.readImage(slot, len);
						boolean keep;
						switch (mode) {
							case EXACT:
							case EQUAL_TO:
								keep = Arrays.equals(cur, needle);
								break;
							case NOT_EQUAL_TO:
								keep = !Arrays.equals(cur, needle);
								break;
							case CHANGED:
								keep = !Arrays.equals(cur, prev);
								break;
							case UNCHANGED:
								keep = Arrays.equals(cur, prev);
								break;
							default:
								keep = false;
						}
						if (keep) {
							if (nb == null) {
								nb = neu.newBlock(ob.regionId, ob.count);
							}
							nb.add(slot, 0, cur);
							neu.added();
						}
					}
				} catch (RuntimeException e) {
					// this candidate disappeared (object gone, array shrank, field inaccessible)
				}
				if ((++done & 0xFFF) == 0) {
					if (cancel.isCancelled()) {
						throw new CancellationException("Scan cancelled");
					}
					long now = System.currentTimeMillis();
					if (now - lastReport > PROGRESS_INTERVAL_MS) {
						lastReport = now;
						prog.onProgress(done, neu.size());
					}
				}
			}
		}
		long ms = System.currentTimeMillis() - t0;
		neu.seal();
		session.update(neu, session.isTruncated(),
				session.step(mode, q.value, neu.size(), ms, false), matchLength);
	}
}
