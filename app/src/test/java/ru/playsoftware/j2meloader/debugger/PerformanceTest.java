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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Coarse performance guards. The bounds are deliberately generous so the tests are stable on
 * slow CI machines; they exist to catch accidental quadratic behaviour, not to benchmark.
 */
public class PerformanceTest extends DebuggerTestBase {
	private static final int BIG = 2_000_000;

	private static long since(long t0) {
		return (System.nanoTime() - t0) / 1_000_000;
	}

	@Test
	public void largeIntArrayTypedScanAndFilter() {
		int[] big = new int[BIG];
		for (int i = 0; i < big.length; i++) {
			big[i] = i % 1000;
		}
		big[1_234_567] = 424242;
		TestGame.big = big;

		long t0 = System.nanoTime();
		ScanSession s = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "424242");
		long first = since(t0);
		assertEquals(1, s.resultCount());

		ScanParams p = params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		p.maxCandidates = 3_000_000;
		t0 = System.nanoTime();
		ScanSession u = dbg.runNewScan(p, null, CancelToken.NEVER);
		long unknown = since(t0);
		assertTrue(u.resultCount() >= BIG);

		big[10] = 777;
		t0 = System.nanoTime();
		dbg.runNextScan(params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.CHANGED, ""), null, CancelToken.NEVER);
		long filter = since(t0);
		assertEquals(1, u.resultCount());

		System.out.println("[perf] int[2M] exact=" + first + " ms, unknown=" + unknown + " ms, changed-filter="
				+ filter + " ms");
		assertTrue("exact scan " + first + " ms", first < 5000);
		assertTrue("unknown scan " + unknown + " ms", unknown < 8000);
		assertTrue("filter " + filter + " ms", filter < 5000);
	}

	@Test
	public void largeByteArrayRawScans() {
		byte[] data = new byte[4 * 1024 * 1024];
		for (int i = 0; i < data.length; i++) {
			data[i] = (byte) (i * 31);
		}
		int at = 3_000_001;
		byte[] marker = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x12};
		System.arraycopy(marker, 0, data, at, marker.length);
		TestGame.big = null;
		TestGame.saveData = data;

		long t0 = System.nanoTime();
		ScanSession bytes = first(ScanScope.RAW, ValueType.BYTES, ScanMode.EXACT, "CA FE BA BE 12");
		long search = since(t0);
		assertEquals(1, bytes.resultCount());
		assertEquals(at, dbg.results(bytes, 0, 1).get(0).location.slot);

		ScanParams p = params(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "0xCAFEBABE");
		p.alignment = 1;
		t0 = System.nanoTime();
		ScanSession ints = dbg.runNewScan(p, null, CancelToken.NEVER);
		long unaligned = since(t0);
		assertEquals(1, ints.resultCount());

		System.out.println("[perf] byte[4M] sequence=" + search + " ms, unaligned int32=" + unaligned + " ms");
		assertTrue("sequence search " + search + " ms", search < 4000);
		assertTrue("unaligned scan " + unaligned + " ms", unaligned < 8000);
	}

	@Test
	public void largeObjectGraphWalk() {
		for (int i = 0; i < 100_000; i++) {
			TestGame.party.add(new TestGame.Player());
		}
		long t0 = System.nanoTime();
		ScanSession s = first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "100");
		long walk = since(t0);
		long candidates = s.resultCount();
		assertTrue(candidates >= 100_000);

		TestGame.party.get(500).hp = 5;
		t0 = System.nanoTime();
		next(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EQUAL_TO, "100");
		long filter = since(t0);
		assertEquals("exactly the modified player dropped out", candidates - 1, s.resultCount());
		System.out.println("[perf] 100k objects walk+scan=" + walk + " ms, filter=" + filter + " ms, results="
				+ s.resultCount());
		assertTrue("walk " + walk + " ms", walk < 15000);
		assertTrue("filter " + filter + " ms (must not enumerate objects again)", filter < walk + 1000);
	}

	@Test
	public void staticScanStaysCheapForTypicalGames() {
		long t0 = System.nanoTime();
		for (int i = 0; i < 100; i++) {
			first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		}
		long ms = since(t0);
		System.out.println("[perf] 100 static unknown scans: " + ms + " ms");
		assertTrue("took " + ms + " ms", ms < 3000);
	}

	@Test
	public void filteringDoesNotWalkTheHeapAgain() {
		for (int i = 0; i < 20_000; i++) {
			TestGame.party.add(new TestGame.Player());
		}
		ScanSession s = first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.UNKNOWN, "");
		long candidates = s.resultCount();
		long t0 = System.nanoTime();
		for (int i = 0; i < 20; i++) {
			next(ScanScope.OBJECTS, ValueType.INT32, ScanMode.UNCHANGED, "");
		}
		long ms = since(t0);
		System.out.println("[perf] 20 filters over " + s.resultCount() + " candidates: " + ms + " ms");
		assertTrue("took " + ms + " ms", ms < 5000);
		assertEquals("nothing changed, nothing dropped", candidates, s.resultCount());
	}

	@Test
	public void scansThatFindNothingLeaveNoRegistryEntries() {
		for (int i = 0; i < 5000; i++) {
			TestGame.party.add(new TestGame.Player());
		}
		TestGame.big = new int[100_000];
		assertTrue(dbg.statsText(), dbg.statsText().contains(" 0 objects"));
		assertEquals(0, first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "123456").resultCount());
		assertEquals(0, first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "123456").resultCount());
		assertEquals(0, first(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "123456").resultCount());
		// thousands of objects and arrays were visited, none of them was given an id or an address
		assertTrue(dbg.statsText(), dbg.statsText().contains(" 0 objects"));
	}

	@Test
	public void freezeTickIsCheapWithManyFreezes() throws Exception {
		dbg.setFreezePeriodMs(20);
		for (int i = 0; i < 50; i++) {
			dbg.addFreeze("f" + i, MemoryReference.staticField(TestGame.class.getName(), "money"),
					ValueType.INT32, true, StringEncoding.UTF8, 0,
					MemoryValue.ofBits(ValueType.INT32, 5000), true);
		}
		Thread.sleep(300);
		long t0 = System.nanoTime();
		for (int i = 0; i < 100; i++) {
			dbg.enforceFrozen();
		}
		long ms = since(t0);
		System.out.println("[perf] 100 ticks x 50 freezes: " + ms + " ms");
		assertTrue("took " + ms + " ms", ms < 2000);
	}
}
