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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

/** Going back to the results of an earlier scan step. */
public class HistoryTest extends DebuggerTestBase {

	private ScanSession threeSteps() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		TestGame.health = 90;
		TestGame.money = 4000;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, ""); // health and money
		TestGame.health = 80;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, ""); // health only
		return s;
	}

	@Test
	public void goingBackRestoresTheEarlierResults() {
		ScanSession s = threeSteps();
		List<ScanSession.Step> h = s.history();
		assertEquals(3, h.size());
		assertEquals(1, s.resultCount());
		long afterFirst = h.get(0).results;
		long afterSecond = h.get(1).results;
		assertEquals(2, afterSecond);

		dbg.restoreScanStep(s, 1);
		assertEquals("results of the second step are back", afterSecond, s.resultCount());
		assertHas(s, G + "health");
		assertHas(s, G + "money");
		assertEquals("later steps are discarded", 2, s.history().size());

		dbg.restoreScanStep(s, 0);
		assertEquals(afterFirst, s.resultCount());
		assertEquals(1, s.history().size());
		assertHas(s, G + "level");
	}

	@Test
	public void scanningContinuesFromTheRestoredStep() {
		ScanSession s = threeSteps();
		dbg.restoreScanStep(s, 1); // health and money, remembered as 90 and 4000
		TestGame.money = 5000;     // money went back up, health stays 80
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, "");
		assertEquals(names(s).toString(), 1, s.resultCount());
		assertHas(s, G + "health"); // compared with the value of the restored step (90), not with 80
		assertEquals(3, s.history().size());
	}

	@Test
	public void restoringTheCurrentStepChangesNothing() {
		ScanSession s = threeSteps();
		long n = s.resultCount();
		dbg.restoreScanStep(s, s.history().size() - 1);
		assertEquals(n, s.resultCount());
		assertEquals(3, s.history().size());
	}

	@Test
	public void invalidStepsAreRejected() {
		ScanSession s = threeSteps();
		for (int bad : new int[]{-1, 3, 99}) {
			try {
				dbg.restoreScanStep(s, bad);
				fail("step " + bad);
			} catch (IllegalArgumentException expected) {
				assertEquals(3, s.history().size());
			}
		}
	}

	@Test
	public void resultsOfAPreviousRunCannotBeRestored() {
		ScanSession s = threeSteps();
		dbg.onMidletLoading();
		dbg.onClassLoaded(TestGame.class);
		try {
			dbg.restoreScanStep(s, 0);
			fail();
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("previous run"));
		}
	}

	@Test
	public void restoredResultsKeepTheirTruncationState() {
		TestGame.big = new int[5000];
		ScanParams p = params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		p.maxCandidates = 1000;
		ScanSession s = dbg.runNewScan(p, null, CancelToken.NEVER);
		assertTrue(s.isTruncated());
		next(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNCHANGED, "");
		dbg.restoreScanStep(s, 0);
		assertTrue("still marked as incomplete", s.isTruncated());
		assertEquals(1000, s.resultCount());
	}

	@Test
	public void oldStepsAreDroppedWhenTheHistoryGetsTooBig() {
		TestGame.big = new int[1_800_000];
		ScanParams p = params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		p.maxCandidates = 3_000_000;
		ScanSession s = dbg.runNewScan(p, null, CancelToken.NEVER);
		long each = s.resultCount();
		assertTrue(each > 1_800_000);
		// another full snapshot: together they are more than a session may keep
		next(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		List<ScanSession.Step> h = s.history();
		assertFalse("the oldest results were dropped", h.get(0).isRestorable());
		assertTrue("the newest are always kept", h.get(1).isRestorable());
		assertTrue(s.retainedCount() <= ScanSession.MAX_RETAINED_CANDIDATES);
		try {
			dbg.restoreScanStep(s, 0);
			fail();
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("not kept"));
		}
		assertEquals("the failed restore changed nothing", 2, s.history().size());
		assertEquals(each, s.resultCount());
	}

	@Test
	public void smallHistoriesAreKeptInFull() {
		ScanSession s = threeSteps();
		for (ScanSession.Step step : s.history()) {
			assertTrue(step.isRestorable());
		}
	}

	@Test
	public void goingBackDoesNotDisturbOtherSessions() {
		ScanSession other = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "5000");
		ScanSession s = threeSteps();
		dbg.restoreScanStep(s, 0);
		assertEquals(1, other.resultCount());
		assertEquals(2, dbg.sessions().size());
		assertEquals(s, dbg.activeSession());
	}
}
