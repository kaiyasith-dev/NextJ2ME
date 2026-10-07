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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

/** The limits that keep the debugger from exhausting the game's heap, and how they are reported. */
public class MemorySafetyTest extends DebuggerTestBase {

	private ScanSession group(ScanScope scope, ValueType type, String values, int window) {
		ScanParams p = params(scope, type, ScanMode.EXACT, values);
		p.group = true;
		p.groupWindow = window;
		return dbg.runNewScan(p, null, CancelToken.NEVER);
	}

	private static boolean anyContains(List<String> list, String text) {
		for (String s : list) {
			if (s.contains(text)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ group scan limits

	@Test
	public void groupScanSkipsRegionsWhereAValueIsTooCommon() {
		TestGame.big = new int[MemoryScanner.GROUP_MATCH_CAP * 2]; // all zeros: 0 matches everywhere
		ScanSession s = group(ScanScope.ARRAYS, ValueType.INT32, "0;0;0", 8);
		assertNotNull("the user is told", s.warning());
		assertTrue(s.warning(), s.warning().contains("skipped"));
		assertTrue(s.warning(), s.warning().contains(String.valueOf(MemoryScanner.GROUP_MATCH_CAP)));
		assertTrue("nothing from the skipped array", s.resultCount() < 100);
	}

	@Test
	public void ordinaryGroupScansGetNoWarning() {
		ScanSession s = group(ScanScope.ARRAYS, ValueType.INT32, "7;100;42", 2);
		assertEquals(3, s.resultCount());
		assertNull(s.warning());
	}

	@Test
	public void commonValuesBelowTheLimitStillWork() {
		TestGame.big = new int[MemoryScanner.GROUP_MATCH_CAP / 2];
		ScanSession s = group(ScanScope.ARRAYS, ValueType.INT32, "0;0", 1);
		assertNull(s.warning());
		assertTrue(s.resultCount() >= MemoryScanner.GROUP_MATCH_CAP / 2);
	}

	@Test
	public void aGroupHasAtMostSixteenValues() {
		StringBuilder ok = new StringBuilder();
		for (int i = 0; i < ScanParams.MAX_GROUP_VALUES; i++) {
			ok.append(i).append(';');
		}
		group(ScanScope.STATIC_FIELDS, ValueType.INT32, ok.toString(), 8); // exactly the limit
		try {
			group(ScanScope.STATIC_FIELDS, ValueType.INT32, ok + "99", 8);
			fail();
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("at most"));
		}
	}

	@Test
	public void groupScanOfAHugeCommonValueStaysCheap() {
		TestGame.big = new int[3_000_000];
		long before = usedMb();
		group(ScanScope.ARRAYS, ValueType.INT32, "0;0;0;0;0;0;0;0", 8);
		long retained = usedMb() - before;
		// the temporary lists are capped, nothing sizeable stays behind
		assertTrue("kept " + retained + " MB", retained < 20);
	}

	private static long usedMb() {
		System.gc();
		System.gc();
		Runtime r = Runtime.getRuntime();
		return (r.totalMemory() - r.freeMemory()) >> 20;
	}

	// ------------------------------------------------------------ exact-size result lists

	@Test
	public void keptResultsHoldNoSpareMemory() {
		TestGame.big = new int[300_000];
		ScanSession s = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		for (MemorySnapshot.Block b : s.snapshot().blocks()) {
			assertTrue("capacity " + b.slots.length + " for " + b.count,
					b.slots.length - b.count <= Math.max(16, b.count >> 3));
			assertEquals(b.slots.length, b.bits.length);
		}
		next(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNCHANGED, "");
		for (MemorySnapshot.Block b : s.snapshot().blocks()) {
			assertTrue(b.slots.length - b.count <= Math.max(16, b.count >> 3));
		}
	}

	@Test
	public void trimmingKeepsEveryResult() {
		TestGame.big = new int[100_000];
		ScanSession s = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		long n = s.resultCount();
		assertTrue(n >= 100_000);
		assertEquals(n, dbg.results(s, 0, (int) n).size());
		TestGame.big[99_999] = 5;
		next(ScanScope.ARRAYS, ValueType.INT32, ScanMode.CHANGED, "");
		assertEquals(1, s.resultCount());
		assertEquals(5, dbg.results(s, 0, 1).get(0).previous.bits);
	}

	// ------------------------------------------------------------ telling the user

	@Test
	public void removingAnOldSessionIsReported() {
		for (int i = 0; i < 7; i++) {
			first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		}
		List<String> notices = dbg.takeNotices();
		assertTrue(notices.toString(), anyContains(notices, "Scan #1 was removed"));
		assertTrue(notices.toString(), anyContains(notices, "at most 6"));
		assertTrue("reading clears them", dbg.takeNotices().isEmpty());
	}

	@Test
	public void sessionsAreRemovedForMemoryAndReported() {
		TestGame.big = new int[1_500_000];
		ScanSession a = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		ScanSession c = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, ""); // over the total limit
		assertFalse("the oldest scan made room", dbg.sessions().contains(a));
		assertSame("the newest scan is kept", c, dbg.activeSession());
		List<String> notices = dbg.takeNotices();
		assertTrue(notices.toString(), anyContains(notices, "Scan #" + a.id + " was removed to save memory"));
	}

	@Test
	public void theActiveScanIsNeverRemovedForLimits() {
		TestGame.big = new int[1_500_000];
		ScanSession a = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		dbg.setActiveSession(a); // the user goes back to the oldest one
		next(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNCHANGED, "");
		assertTrue("the scan being worked on stays", dbg.sessions().contains(a));
		assertSame(a, dbg.activeSession());
	}

	@Test
	public void droppedHistoryStepsAreReported() {
		TestGame.big = new int[1_800_000];
		first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		dbg.takeNotices();
		next(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, ""); // two full snapshots: too much to keep
		List<String> notices = dbg.takeNotices();
		assertTrue(notices.toString(), anyContains(notices, "the results of 1 older step were dropped to save memory"));
	}

	@Test
	public void smallHistoriesProduceNoNotices() {
		first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNCHANGED, "");
		assertTrue(dbg.takeNotices().isEmpty());
	}

	// ------------------------------------------------------------ out of memory

	@Test
	public void releasingMemoryKeepsOnlyTheActiveScanAndItsNewestResults() {
		ScanSession a = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		ScanSession c = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		TestGame.health = 90;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, "");
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNCHANGED, "");
		dbg.takeNotices();

		dbg.releaseMemory();

		assertEquals(1, dbg.sessions().size());
		assertSame(c, dbg.activeSession());
		assertFalse(dbg.sessions().contains(a));
		List<ScanSession.Step> steps = c.history();
		assertFalse(steps.get(0).isRestorable());
		assertFalse(steps.get(1).isRestorable());
		assertTrue("the current results are untouched", steps.get(2).isRestorable());
		assertEquals(1, c.resultCount());
		List<String> notices = dbg.takeNotices();
		assertTrue(notices.toString(), anyContains(notices, "was removed to free memory"));
		assertTrue(notices.toString(), anyContains(notices, "older steps dropped to free memory"));
		// the scan still works afterwards
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNCHANGED, "");
		assertEquals(1, c.resultCount());
	}

	@Test
	public void releasingMemoryWithoutScansIsHarmless() {
		dbg.releaseMemory();
		assertTrue(dbg.sessions().isEmpty());
		assertTrue(dbg.takeNotices().isEmpty());
	}
}
