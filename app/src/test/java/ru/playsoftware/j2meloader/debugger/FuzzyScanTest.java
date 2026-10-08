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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/** The "any integer size" scan: one value searched as every integer width it fits. */
public class FuzzyScanTest extends DebuggerTestBase {

	private static ScanParams fuzzy(ScanScope scope, String value) {
		ScanParams p = params(scope, ValueType.INT32, ScanMode.EXACT, value);
		p.fuzzy = true;
		return p;
	}

	private ScanSession fuzzyScan(ScanScope scope, String value) {
		return dbg.runNewScan(fuzzy(scope, value), null, CancelToken.NEVER);
	}

	private ScanSession fuzzyNext(ScanMode mode, String value) {
		return dbg.runNextScan(params(ScanScope.ARRAYS, ValueType.INT32, mode, value), null, CancelToken.NEVER);
	}

	private List<ValueType> typesOfSessions() {
		List<ValueType> out = new ArrayList<>();
		for (ScanSession s : dbg.sessions()) {
			out.add(s.type);
		}
		return out;
	}

	// ------------------------------------------------------------ which sizes a value fits

	private static List<ValueType> fuzzyTypes(String value) {
		ScanParams p = fuzzy(ScanScope.OBJECTS, value);
		return Arrays.asList(p.fuzzyTypes());
	}

	@Test
	public void aSmallValueIsTriedAtEverySize() {
		assertEquals(Arrays.asList(ValueType.INT8, ValueType.INT16, ValueType.INT32, ValueType.INT64),
				fuzzyTypes("87"));
		assertEquals(Arrays.asList(ValueType.INT8, ValueType.INT16, ValueType.INT32, ValueType.INT64),
				fuzzyTypes("-1"));
	}

	@Test
	public void aBiggerValueSkipsTheSizesItDoesNotFit() {
		assertEquals(Arrays.asList(ValueType.UINT8, ValueType.INT16, ValueType.INT32, ValueType.INT64),
				fuzzyTypes("200"));
		assertEquals(Arrays.asList(ValueType.INT32, ValueType.INT64), fuzzyTypes("70000"));
		assertEquals(Arrays.asList(ValueType.UINT32, ValueType.INT64), fuzzyTypes("3000000000"));
		assertEquals("the largest unsigned 64-bit value only fits 64 bits", 1,
				fuzzyTypes("18446744073709551615").size());
	}

	@Test
	public void hexValuesAreAccepted() {
		assertFalse(fuzzyTypes("0x7F").isEmpty());
	}

	@Test
	public void valuesThatAreNotWholeNumbersAreRefused() {
		for (String bad : new String[]{"", "  ", "abc", "1.5", "99999999999999999999999"}) {
			try {
				fuzzy(ScanScope.OBJECTS, bad).fuzzyTypes();
				fail("expected a refusal for '" + bad + "'");
			} catch (IllegalArgumentException expected) {
				assertNotNull(expected.getMessage());
			}
		}
	}

	@Test
	public void onlyExactValueIsAllowedAndNotTogetherWithAGroup() {
		ScanParams p = fuzzy(ScanScope.OBJECTS, "5");
		p.mode = ScanMode.INCREASED;
		try {
			p.validate(true);
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("Exact"));
		}
		p = fuzzy(ScanScope.OBJECTS, "5");
		p.group = true;
		try {
			p.validate(true);
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("group"));
		}
	}

	@Test
	public void itCannotBeUsedForTheFollowingScans() {
		ScanParams p = fuzzy(ScanScope.OBJECTS, "5");
		try {
			p.validate(false);
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("new scan"));
		}
	}

	// ------------------------------------------------------------ the scan

	@Test
	public void eachSizeWithResultsBecomesAScanOfItsOwn() {
		// 100 is a byte in saveData, and an int twice in inventory
		ScanSession active = fuzzyScan(ScanScope.ARRAYS, "100");
		assertEquals(ValueType.INT32, active.type);
		assertEquals(2, active.resultCount());
		assertEquals(Arrays.asList(ValueType.INT8, ValueType.INT32), typesOfSessions());
		for (ScanSession s : dbg.sessions()) {
			assertEquals("all belong to one group", active.fuzzyGroup(), s.fuzzyGroup());
			assertTrue(s.fuzzyGroup() != 0);
		}
		assertTrue(active.title().contains("any size"));
		ScanSession bytes = dbg.sessions().get(0);
		assertEquals(1, bytes.resultCount());
		assertHas(bytes, "byte[7]");
		assertHas(active, "int[6]");
	}

	@Test
	public void theValueIsFoundInFieldsOfTheirOwnSize() {
		// Player.mp is a short, Player.hp an int
		ScanSession shorts = fuzzyScan(ScanScope.OBJECTS, "30");
		assertEquals(ValueType.INT16, shorts.type);
		assertHas(shorts, "mp");
		ScanSession ints = fuzzyScan(ScanScope.OBJECTS, "100");
		assertEquals(ValueType.INT32, ints.type);
		assertHas(ints, "hp");
	}

	@Test
	public void aValueThatIsNowhereGivesOneEmptyScan() {
		ScanSession s = fuzzyScan(ScanScope.OBJECTS, "31337");
		assertEquals(0, s.resultCount());
		assertEquals("the common 32-bit size is shown", ValueType.INT32, s.type);
		assertEquals(1, dbg.sessions().size());
	}

	@Test
	public void ordinaryScansAreNotAffected() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		assertEquals(0, s.fuzzyGroup());
		assertFalse(s.title().contains("any size"));
	}

	// ------------------------------------------------------------ narrowing it down

	@Test
	public void theFollowingScansFilterEverySizeAndTheWrongOnesDisappear() {
		fuzzyScan(ScanScope.ARRAYS, "100");
		assertEquals(2, dbg.sessions().size());
		TestGame.inventory[2] = 150;           // only the int changes
		ScanSession now = fuzzyNext(ScanMode.EXACT, "150");
		assertEquals(ValueType.INT32, now.type);
		assertEquals(1, now.resultCount());
		assertEquals("the byte scan had no match any more", Arrays.asList(ValueType.INT32), typesOfSessions());
		List<String> notices = dbg.takeNotices();
		assertEquals(1, notices.size());
		assertTrue(notices.get(0), notices.get(0).contains("int8"));
		assertTrue(notices.get(0), notices.get(0).contains("kept int32"));
	}

	@Test
	public void aSizeThatCannotHoldTheNewValueIsDroppedNotAnError() {
		fuzzyScan(ScanScope.ARRAYS, "100");
		TestGame.inventory[2] = 1000;           // does not fit a byte
		ScanSession now = fuzzyNext(ScanMode.EXACT, "1000");
		assertEquals(ValueType.INT32, now.type);
		assertEquals(1, now.resultCount());
		assertEquals(Arrays.asList(ValueType.INT32), typesOfSessions());
	}

	@Test
	public void comparisonModesWorkOnEverySize() {
		fuzzyScan(ScanScope.ARRAYS, "100");
		TestGame.inventory[2] = 101;
		TestGame.saveData[5] = 100;             // the byte stays the same
		ScanSession now = fuzzyNext(ScanMode.CHANGED, "");
		assertEquals(Arrays.asList(ValueType.INT32), typesOfSessions());
		assertEquals(1, now.resultCount());
		assertHas(now, "int[6]");
	}

	@Test
	public void ifNothingMatchesAnyMoreOneEmptyScanIsKept() {
		fuzzyScan(ScanScope.ARRAYS, "100");
		ScanSession now = fuzzyNext(ScanMode.EXACT, "55");
		assertEquals(0, now.resultCount());
		assertEquals(1, dbg.sessions().size());
		assertSame(now, dbg.activeSession());
	}

	private static void assertSame(Object expected, Object actual) {
		org.junit.Assert.assertSame(expected, actual);
	}

	@Test
	public void aValueThatIsNotANumberIsReportedAndNothingChanges() {
		fuzzyScan(ScanScope.ARRAYS, "100");
		try {
			fuzzyNext(ScanMode.EXACT, "abc");
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			assertNotNull(expected.getMessage());
		}
		assertEquals(2, dbg.sessions().size());
		for (ScanSession s : dbg.sessions()) {
			assertEquals("no step was added", 1, s.history().size());
		}
	}

	@Test
	public void cancellingLeavesEverySizeAsItWas() {
		fuzzyScan(ScanScope.ARRAYS, "100");
		CancelToken cancelled = new CancelToken();
		cancelled.cancel();
		try {
			dbg.runNextScan(params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "100"), null, cancelled);
			fail("expected a cancellation");
		} catch (CancellationException expected) {
			// ok
		}
		assertEquals(2, dbg.sessions().size());
		for (ScanSession s : dbg.sessions()) {
			assertEquals(1, s.history().size());
			assertTrue(s.resultCount() > 0);
		}
	}

	@Test
	public void goingBackInTheHistoryStillWorksPerSize() {
		fuzzyScan(ScanScope.ARRAYS, "100");
		TestGame.inventory[2] = 150;
		ScanSession now = fuzzyNext(ScanMode.EXACT, "150");
		assertEquals(2, now.history().size());
		dbg.restoreScanStep(now, 0);
		assertEquals(2, now.resultCount());
	}

	@Test
	public void theResultsOfASizeCanBeWatchedLikeAnyOther() {
		ScanSession active = fuzzyScan(ScanScope.ARRAYS, "100");
		MemoryDebugger.ScanResult r = find(active, "int[6]");
		assertEquals(ValueType.INT32, r.location.type);
		assertEquals(100, dbg.read(r.location).bits);
	}

	// ------------------------------------------------------------ limits and persistence

	@Test
	public void theCandidateLimitIsSharedBetweenTheSizes() {
		TestGame.big = new int[1000];            // 1000 zeros as int32
		ScanParams p = fuzzy(ScanScope.ARRAYS, "0");
		p.maxCandidates = 400;                   // 4 sizes: 100 each
		ScanSession s = dbg.runNewScan(p, null, CancelToken.NEVER);
		for (ScanSession each : dbg.sessions()) {
			assertTrue(each.title(), each.resultCount() <= 100);
		}
		assertTrue("the int32 scan hit its share", s.isTruncated() || s.resultCount() <= 100);
		long total = 0;
		for (ScanSession each : dbg.sessions()) {
			total += each.resultCount();
		}
		assertTrue("together no more than a normal scan would keep", total <= 400);
	}

	@Test
	public void theSettingIsRememberedWithTheScanSettings() throws Exception {
		fuzzyScan(ScanScope.ARRAYS, "100");
		assertTrue(dbg.settings().scan.fuzzy);
		assertTrue(dbg.saveNow());
		dbg.onMidletDestroyed();
		TestGame.reset();
		game = new TestGame();
		dbg = newDebugger();
		assertTrue(dbg.settings().scan.fuzzy);
		assertEquals("100", dbg.settings().scan.value);
		assertNull("scans themselves are not restored", dbg.activeSession());
	}

	@Test
	public void olderScansMakeRoomWithoutLosingTheNewGroup() {
		for (int i = 0; i < 5; i++) {
			first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		}
		ScanSession active = fuzzyScan(ScanScope.ARRAYS, "100");
		assertTrue("at most 6 scans are kept", dbg.scanCount() <= 6);
		int inGroup = 0;
		for (ScanSession s : dbg.sessions()) {
			if (s.fuzzyGroup() == active.fuzzyGroup()) {
				inGroup++;
			}
		}
		assertEquals("both sizes of the new scan survive", 2, inGroup);
		fuzzyScan(ScanScope.ARRAYS, "100");
		fuzzyScan(ScanScope.ARRAYS, "100");
		assertTrue(dbg.scanCount() <= 6);
		for (ScanSession s : dbg.sessions()) {
			if (s.fuzzyGroup() != 0) {
				assertEquals("a scan is removed whole, never one size of it",
						2, dbg.scanGroup(s).size());
			}
		}
	}

	// ------------------------------------------------------------ all sizes together

	@Test
	public void theSizesCountAsOneScan() {
		fuzzyScan(ScanScope.ARRAYS, "100");
		assertEquals(2, dbg.sessions().size());
		assertEquals(1, dbg.scanCount());
		first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		assertEquals(2, dbg.scanCount());
	}

	@Test
	public void theTotalAndTheSizeSummaryCoverEverySize() {
		ScanSession s = fuzzyScan(ScanScope.ARRAYS, "100");
		assertEquals(3, dbg.totalResults(s));
		assertEquals("int8 1 \u00b7 int32 2", dbg.sizeSummary(s));
		assertFalse(dbg.isTruncated(s));
		ScanSession plain = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		assertNull(dbg.sizeSummary(plain));
		assertEquals(plain.resultCount(), dbg.totalResults(plain));
	}

	@Test
	public void theCombinedListHasEveryAddressOfEverySize() {
		ScanSession s = fuzzyScan(ScanScope.ARRAYS, "100");
		List<MemoryDebugger.ScanResult> all = dbg.combinedResults(s, 0, 100);
		assertEquals(3, all.size());
		assertEquals(ValueType.INT8, all.get(0).location.type);
		assertEquals(ValueType.INT32, all.get(1).location.type);
		assertEquals(ValueType.INT32, all.get(2).location.type);
		for (MemoryDebugger.ScanResult r : all) {
			assertEquals(100, dbg.read(r.location).bits);
		}
	}

	@Test
	public void theCombinedListCanBePagedAcrossTheSizes() {
		ScanSession s = fuzzyScan(ScanScope.ARRAYS, "100");
		List<MemoryDebugger.ScanResult> page = dbg.combinedResults(s, 1, 1);
		assertEquals(1, page.size());
		assertEquals(ValueType.INT32, page.get(0).location.type);
		assertEquals(1, dbg.combinedResults(s, 2, 10).size());
		assertEquals(0, dbg.combinedResults(s, 3, 10).size());
		assertEquals(2, dbg.combinedResults(s, 0, 2).size());
		assertEquals(ValueType.INT8, dbg.combinedResults(s, 0, 2).get(0).location.type);
	}

	@Test
	public void anOrdinaryScanListsItsOwnResultsOnly() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		assertEquals(s.resultCount(), dbg.combinedResults(s, 0, 100).size());
		assertEquals(1, dbg.scanGroup(s).size());
	}

	@Test
	public void resetRemovesEverySizeOfTheScan() {
		first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		fuzzyScan(ScanScope.ARRAYS, "100");
		assertEquals(3, dbg.sessions().size());
		dbg.resetScan();
		assertEquals("the any-size scan went with both of its sizes", 1, dbg.sessions().size());
		assertEquals(0, dbg.sessions().get(0).fuzzyGroup());
	}

	@Test
	public void historyCountsAndGoingBackCoverEverySize() {
		ScanSession s = fuzzyScan(ScanScope.ARRAYS, "100");
		assertEquals(3, dbg.stepResults(s, 0));
		fuzzyNext(ScanMode.UNCHANGED, "");            // both sizes keep their results
		assertEquals(2, s.history().size());
		assertEquals(3, dbg.stepResults(s, 1));
		fuzzyNext(ScanMode.EXACT, "100");
		assertEquals(3, s.history().size());
		dbg.restoreScanStep(s, 0);
		for (ScanSession m : dbg.scanGroup(s)) {
			assertEquals("every size went back to the first step", 1, m.history().size());
		}
		assertEquals(3, dbg.totalResults(s));
	}

	@Test
	public void goingBackIsRefusedForAllSizesIfOneCannotGoBack() {
		ScanSession s = fuzzyScan(ScanScope.ARRAYS, "100");
		fuzzyNext(ScanMode.UNCHANGED, "");
		try {
			dbg.restoreScanStep(s, 5);
			fail("expected a refusal");
		} catch (IllegalArgumentException expected) {
			// ok
		}
		for (ScanSession m : dbg.scanGroup(s)) {
			assertEquals("nothing was changed", 2, m.history().size());
		}
	}
}
