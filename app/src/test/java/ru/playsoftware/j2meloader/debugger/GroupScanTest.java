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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class GroupScanTest extends DebuggerTestBase {

	private ScanSession group(ScanScope scope, ValueType type, String values, int window, boolean ordered) {
		ScanParams p = params(scope, type, ScanMode.EXACT, values);
		p.group = true;
		p.groupWindow = window;
		p.groupOrdered = ordered;
		return dbg.runNewScan(p, null, CancelToken.NEVER);
	}

	private static GroupMatcher.IntList list(int... v) {
		GroupMatcher.IntList l = new GroupMatcher.IntList();
		for (int x : v) {
			l.add(x);
		}
		return l;
	}

	// ------------------------------------------------------------ the matching rules

	@Test
	public void unorderedUsesTheNearestOccurrenceAndAWindow() {
		GroupMatcher.IntList[] lists = {list(1), list(2, 4), list(3)};
		assertArrayEquals(new int[]{1, 2, 3}, GroupMatcher.match(lists, 2, false).sortedUnique());
		assertEquals("the third value is 2 away from the anchor",
				0, GroupMatcher.match(lists, 1, false).size);
	}

	@Test
	public void orderedNeedsIncreasingPositionsWithBoundedGaps() {
		GroupMatcher.IntList[] lists = {list(1), list(2, 4), list(3)};
		assertArrayEquals(new int[]{1, 2, 3}, GroupMatcher.match(lists, 1, true).sortedUnique());
		GroupMatcher.IntList[] reversed = {list(3), list(1)};
		assertEquals(0, GroupMatcher.match(reversed, 10, true).size);
		GroupMatcher.IntList[] gap = {list(0), list(5)};
		assertEquals(0, GroupMatcher.match(gap, 4, true).size);
		assertArrayEquals(new int[]{0, 5}, GroupMatcher.match(gap, 5, true).sortedUnique());
	}

	@Test
	public void sameValueTwiceNeedsTwoDistinctPositions() {
		GroupMatcher.IntList[] twice = {list(2, 4), list(2, 4)};
		assertArrayEquals(new int[]{2, 4}, GroupMatcher.match(twice, 2, false).sortedUnique());
		GroupMatcher.IntList[] once = {list(2), list(2)};
		assertEquals("one occurrence cannot serve two values", 0, GroupMatcher.match(once, 5, false).size);
	}

	@Test
	public void missingValueMeansNoGroup() {
		GroupMatcher.IntList[] lists = {list(1, 2), list()};
		assertEquals(0, GroupMatcher.match(lists, 100, false).size);
	}

	// ------------------------------------------------------------ in a running game

	@Test
	public void staticFieldsGroupFindsTheStatsTogether() {
		ScanSession s = group(ScanScope.STATIC_FIELDS, ValueType.INT32, "100;5000;1", 8, false);
		assertEquals(names(s).toString(), 3, s.resultCount());
		assertHas(s, G + "health");
		assertHas(s, G + "money");
		assertHas(s, G + "level");
		assertHasNot(s, G + "hidden");
		assertEquals(0, group(ScanScope.STATIC_FIELDS, ValueType.INT32, "100;5001", 8, false).resultCount());
	}

	@Test
	public void objectGroupFindsEveryObjectHoldingAllValues() {
		ScanSession s = group(ScanScope.OBJECTS, ValueType.INT32, "100;250", 8, false);
		List<String> names = names(s);
		// two Player objects: the static one (also held by the game) and the one in the party
		assertEquals(names.toString(), 4, s.resultCount());
		int hp = 0;
		int gold = 0;
		for (String n : names) {
			hp += n.endsWith(".hp") ? 1 : 0;
			gold += n.endsWith(".gold") ? 1 : 0;
		}
		assertEquals(2, hp);
		assertEquals(2, gold);
		TestGame.party.get(0).gold = 1;
		assertEquals("only one object still has both", 2,
				group(ScanScope.OBJECTS, ValueType.INT32, "100;250", 8, false).resultCount());
	}

	@Test
	public void arrayElementsHonourWindowAndOrder() {
		// inventory = {3, 7, 100, 42, 100, 9}
		assertEquals(3, group(ScanScope.ARRAYS, ValueType.INT32, "7;100;42", 2, false).resultCount());
		assertEquals(0, group(ScanScope.ARRAYS, ValueType.INT32, "7;100;42", 1, false).resultCount());
		assertEquals(3, group(ScanScope.ARRAYS, ValueType.INT32, "7;100;42", 1, true).resultCount());
		assertEquals("wrong order", 0, group(ScanScope.ARRAYS, ValueType.INT32, "42;7", 6, true).resultCount());
		assertEquals(0, group(ScanScope.ARRAYS, ValueType.INT32, "3;9", 4, false).resultCount());
		assertEquals(2, group(ScanScope.ARRAYS, ValueType.INT32, "3;9", 5, false).resultCount());
		ScanSession dup = group(ScanScope.ARRAYS, ValueType.INT32, "100;100", 2, false);
		assertEquals(names(dup).toString(), 2, dup.resultCount());
		assertTrue(names(dup).toString(), names(dup).get(0).endsWith("[2]"));
	}

	@Test
	public void rawMemoryGroupUsesPositionsOfTheTypeWidth() {
		// 7 at byte 4, 100 at byte 8, 42 at byte 12 inside the int[] image
		ScanSession s = group(ScanScope.RAW, ValueType.INT32, "7;100;42", 2, true);
		assertEquals(names(s).toString(), 3, s.resultCount());
		assertEquals(0, group(ScanScope.RAW, ValueType.INT32, "7;42", 1, false).resultCount());
		// bytes 1 2 0 0 0 100 9 of saveData: a group of two neighbouring bytes
		ScanParams p = params(ScanScope.RAW, ValueType.INT8, ScanMode.EXACT, "1;2");
		p.group = true;
		p.groupWindow = 1;
		p.groupOrdered = true;
		assertEquals(2, dbg.runNewScan(p, null, CancelToken.NEVER).resultCount());
	}

	@Test
	public void resultsCanBeFilteredLikeAnyScan() {
		ScanSession s = group(ScanScope.STATIC_FIELDS, ValueType.INT32, "100;5000;1", 8, false);
		TestGame.health = 80;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, "");
		assertEquals(1, s.resultCount());
		assertHas(s, G + "health");
		assertEquals("group scan plus one filter step", 2, s.history().size());
	}

	@Test
	public void trailingSeparatorAndWhitespaceAreAccepted() {
		assertEquals(2, group(ScanScope.STATIC_FIELDS, ValueType.INT32, " 100 ; 5000 ;", 8, false).resultCount());
	}

	@Test
	public void negativeAndFloatValues() {
		TestGame.level = -3;
		TestGame.money = 12;
		ScanSession s = group(ScanScope.STATIC_FIELDS, ValueType.INT32, "-3;12", 8, false);
		assertEquals(names(s).toString(), 2, s.resultCount());
		TestGame.speed = 2.5f;
		assertEquals(0, group(ScanScope.STATIC_FIELDS, ValueType.FLOAT, "2.5;1.0", 8, false).resultCount());
	}

	@Test
	public void invalidGroupsAreRejectedWithReadableMessages() {
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100", "at least two");
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100;;5", "Empty value");
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100;abc", "Value 2");
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100;99999999999", "Value 2");
		expectBad(ScanScope.RAW, ValueType.BYTES, ScanMode.EXACT, "00;01", "numeric");
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "100;200", "Exact value");
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT8, ScanMode.EXACT, "1;300", "Value 2");
	}

	private void expectBad(ScanScope scope, ValueType type, ScanMode mode, String value, String message) {
		ScanParams p = params(scope, type, mode, value);
		p.group = true;
		try {
			dbg.runNewScan(p, null, CancelToken.NEVER);
			fail("expected a rejection of '" + value + "'");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(message));
		}
	}

	@Test
	public void windowMustBeSensible() {
		ScanParams p = params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "1;2");
		p.group = true;
		p.groupWindow = 0;
		try {
			dbg.runNewScan(p, null, CancelToken.NEVER);
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Window"));
		}
	}

	@Test
	public void groupIsOnlyForANewScan() {
		first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		ScanParams p = params(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100;5000");
		p.group = true;
		try {
			dbg.runNextScan(p, null, CancelToken.NEVER);
			fail();
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("new scan"));
		}
	}

	@Test
	public void groupSettingsArePersistedPerGame() throws Exception {
		ScanParams p = params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "7;100;42");
		p.group = true;
		p.groupWindow = 3;
		p.groupOrdered = true;
		dbg.runNewScan(p, null, CancelToken.NEVER);
		assertTrue(dbg.saveNow());
		dbg.onMidletDestroyed();
		dbg = newDebugger();
		ScanParams q = dbg.settings().scan;
		assertTrue(q.group);
		assertEquals(3, q.groupWindow);
		assertTrue(q.groupOrdered);
		assertEquals("7;100;42", q.value);
		assertFalse(dbg.sessions().size() > 0);
	}

	@Test
	public void largeArrayGroupStaysFast() {
		int[] big = new int[2_000_000];
		for (int i = 0; i < big.length; i++) {
			big[i] = i % 1000;
		}
		big[1_500_000] = 111111;
		big[1_500_002] = 222222;
		TestGame.big = big;
		long t0 = System.nanoTime();
		ScanSession s = group(ScanScope.ARRAYS, ValueType.INT32, "111111;222222", 4, true);
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("[perf] group scan over int[2M]: " + ms + " ms");
		assertEquals(2, s.resultCount());
		assertTrue("took " + ms + " ms", ms < 5000);
	}
}
