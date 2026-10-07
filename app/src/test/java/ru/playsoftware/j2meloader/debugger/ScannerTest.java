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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class ScannerTest extends DebuggerTestBase {

	// ------------------------------------------------------------ exact value

	@Test
	public void exactIntInStaticFields() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		assertHas(s, G + "health");
		assertHasNot(s, G + "money");
		assertHasNot(s, G + "level");
		assertEquals(1, s.resultCount());
	}

	@Test
	public void privateStaticsAreScannedButConstantsAreNot() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "777");
		assertHas(s, G + "hidden");
		assertEquals(0, first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "99").resultCount());
	}

	@Test
	public void exactIntInObjectFields() {
		ScanSession s = first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "4242");
		assertHas(s, "TestGame #0x");
		assertHas(s, ".score");
		ScanSession hp = first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "100");
		// the static player, the player held by the instance field and the one in the party vector
		assertTrue(names(hp).toString(), hp.resultCount() >= 2);
		assertHas(hp, "Player #0x");
	}

	@Test
	public void byteScanningTypedAndUnsigned() {
		ScanSession s = first(ScanScope.OBJECTS, ValueType.INT8, ScanMode.EXACT, "7");
		assertHas(s, ".level8");
		// signed and unsigned bytes share storage
		ScanSession u = first(ScanScope.OBJECTS, ValueType.UINT8, ScanMode.EXACT, "7");
		assertHas(u, ".level8");
		TestGame.player.level8 = (byte) 200;
		ScanSession hi = first(ScanScope.OBJECTS, ValueType.UINT8, ScanMode.EXACT, "200");
		assertHas(hi, ".level8");
		ScanSession neg = first(ScanScope.OBJECTS, ValueType.INT8, ScanMode.EXACT, "-56");
		assertHas(neg, ".level8");
	}

	@Test
	public void shortCharLongDoubleBooleanFields() {
		assertHas(first(ScanScope.OBJECTS, ValueType.INT16, ScanMode.EXACT, "30"), ".mp");
		assertHas(first(ScanScope.OBJECTS, ValueType.UINT16, ScanMode.EXACT, "65"), ".grade");
		assertHas(first(ScanScope.OBJECTS, ValueType.INT64, ScanMode.EXACT, "123456789012"), ".xp");
		assertHas(first(ScanScope.OBJECTS, ValueType.DOUBLE, ScanMode.EXACT, "0.5"), ".luck");
		assertHas(first(ScanScope.STATIC_FIELDS, ValueType.BOOLEAN, ScanMode.EXACT, "false"), G + "godMode");
		assertEquals(0, first(ScanScope.STATIC_FIELDS, ValueType.BOOLEAN, ScanMode.EXACT, "true").resultCount());
	}

	@Test
	public void floatScanning() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.FLOAT, ScanMode.EXACT, "1.0");
		assertHas(s, G + "speed");
		TestGame.speed = 1.4f;
		assertHas(first(ScanScope.STATIC_FIELDS, ValueType.FLOAT, ScanMode.EXACT, "1.4"), G + "speed");
		assertEquals(0, first(ScanScope.STATIC_FIELDS, ValueType.FLOAT, ScanMode.EXACT, "1.5").resultCount());
	}

	@Test
	public void stringFields() {
		// exact value on text is a substring search, equal-to is a full match
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.STRING, ScanMode.EXACT, "ero");
		assertHas(s, G + "playerName");
		assertEquals(0, first(ScanScope.STATIC_FIELDS, ValueType.STRING, ScanMode.EQUAL_TO, "ero").resultCount());
		assertEquals(1, first(ScanScope.STATIC_FIELDS, ValueType.STRING, ScanMode.EQUAL_TO, "Hero").resultCount());
		assertHas(first(ScanScope.OBJECTS, ValueType.STRING, ScanMode.EXACT, "Squire"), ".title");
	}

	// ------------------------------------------------------------ unknown value and filters

	@Test
	public void unknownInitialValueThenDecreased() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		assertTrue(s.resultCount() >= 4); // health, money, level, hidden
		TestGame.health = 90;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, "");
		assertEquals(names(s).toString(), 1, s.resultCount());
		assertHas(s, G + "health");
		TestGame.health = 95;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.INCREASED, "");
		assertHas(s, G + "health");
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNCHANGED, "");
		assertEquals(1, s.resultCount());
		TestGame.health = 1;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.CHANGED, "");
		assertEquals(1, s.resultCount());
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EQUAL_TO, "1");
		assertEquals(1, s.resultCount());
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.NOT_EQUAL_TO, "1");
		assertEquals(0, s.resultCount());
	}

	@Test
	public void increasedAndDecreasedBy() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		TestGame.health += 30;
		TestGame.money -= 25;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.INCREASED_BY, "30");
		assertEquals(names(s).toString(), 1, s.resultCount());
		assertHas(s, G + "health");

		ScanSession t = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		TestGame.money -= 25;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED_BY, "25");
		assertHas(t, G + "money");
		assertEquals(1, t.resultCount());
	}

	@Test
	public void floatFilters() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.FLOAT, ScanMode.UNKNOWN, "");
		TestGame.speed = 2.5f;
		next(ScanScope.STATIC_FIELDS, ValueType.FLOAT, ScanMode.INCREASED_BY, "1.5");
		assertEquals(1, s.resultCount());
		next(ScanScope.STATIC_FIELDS, ValueType.FLOAT, ScanMode.INCREASED, "");
		assertEquals("nothing changed since the last scan", 0, s.resultCount());
	}

	@Test
	public void snapshotValuesAdvanceWithEveryStep() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		TestGame.health = 80;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.CHANGED, "");
		assertEquals(80, dbg.results(s, 0, 10).get(0).previous.bits);
		TestGame.health = 60;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, "");
		// compared against 80 (previous step), not against the original 100
		assertEquals(60, dbg.results(s, 0, 10).get(0).previous.bits);
		TestGame.health = 70;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.INCREASED, "");
		assertEquals(1, s.resultCount());
		assertEquals(4, s.history().size());
	}

	@Test
	public void historyIsRecorded() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		TestGame.health = 100;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EQUAL_TO, "100");
		List<ScanSession.Step> h = s.history();
		assertEquals(2, h.size());
		assertEquals(ScanMode.EXACT, h.get(0).mode);
		assertEquals("100", h.get(0).value);
		assertEquals(1, h.get(1).results);
	}

	@Test
	public void nextScanNeedsAPreviousScan() {
		try {
			next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, "");
			fail();
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("scan"));
		}
		try {
			first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, "");
			fail();
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("previous scan"));
		}
	}

	@Test
	public void invalidCombinationsAreRejectedWithReadableMessages() {
		expectBad(ScanScope.STATIC_FIELDS, ValueType.BYTES, ScanMode.EXACT, "00");
		expectBad(ScanScope.ARRAYS, ValueType.STRING, ScanMode.EXACT, "x");
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "");
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "12x");
		expectBad(ScanScope.STATIC_FIELDS, ValueType.INT8, ScanMode.EXACT, "300");
		expectBad(ScanScope.RAW, ValueType.STRING, ScanMode.UNKNOWN, "");
	}

	private void expectBad(ScanScope scope, ValueType type, ScanMode mode, String value) {
		try {
			first(scope, type, mode, value);
			fail("expected a rejection for " + scope + " " + type + " " + mode + " '" + value + "'");
		} catch (IllegalArgumentException expected) {
			assertNotNull(expected.getMessage());
		}
	}

	// ------------------------------------------------------------ arrays

	@Test
	public void arrayElementsAreTypedSlots() {
		ScanSession s = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "100");
		List<String> names = names(s);
		assertTrue(names.toString(), names.contains("int[6] #0x" + hex(idOf(TestGame.inventory)) + "[2]"));
		assertTrue(names.toString(), names.contains("int[6] #0x" + hex(idOf(TestGame.inventory)) + "[4]"));
		assertEquals(2, s.resultCount());
		assertHas(first(ScanScope.ARRAYS, ValueType.INT8, ScanMode.EXACT, "30"), "byte[4]");
	}

	private long idOf(Object o) {
		// ids are handed out by the registry; resolving through a scan result proves they are stable
		ScanSession s = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "42");
		return s.snapshot().page(0, 1).get(0).regionId;
	}

	private static String hex(long id) {
		return String.format("%08X", id);
	}

	// ------------------------------------------------------------ raw memory

	@Test
	public void rawIntScanAlignedFindsArrayElements() {
		ScanSession s = first(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "100");
		// two int[] elements; the odd-offset 00 00 00 64 inside saveData is not 4-aligned
		assertEquals(names(s).toString(), 2, s.resultCount());
	}

	@Test
	public void rawIntScanUnalignedFindsMoreAndAddressesAreVirtual() {
		ScanParams p = params(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "100");
		p.alignment = 1;
		ScanSession s = dbg.runNewScan(p, null, CancelToken.NEVER);
		assertEquals(names(s).toString(), 3, s.resultCount());
		for (String n : names(s)) {
			assertTrue(n, n.startsWith("0x0000"));
			long addr = AddressSpace.parse(n);
			assertTrue("virtual space, not host pointers: " + n, addr >= 0x1000 && addr < 0x100000);
		}
	}

	@Test
	public void rawByteOrderLittleEndian() {
		TestGame.saveData = new byte[]{0x64, 0, 0, 0, 9};
		ScanParams little = params(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "100");
		little.bigEndian = false;
		ScanSession le = dbg.runNewScan(little, null, CancelToken.NEVER);
		// bytes 64 00 00 00 read as little endian 100; the int[] images (00 00 00 64) do not
		assertEquals(names(le).toString(), 1, le.resultCount());
		ScanParams big = params(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "100");
		ScanSession be = dbg.runNewScan(big, null, CancelToken.NEVER);
		assertEquals(names(be).toString(), 2, be.resultCount());
		// the same inventory images read as little endian give 0x64000000
		ScanParams swapped = params(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "0x64000000");
		swapped.bigEndian = false;
		assertEquals(2, dbg.runNewScan(swapped, null, CancelToken.NEVER).resultCount());
	}

	@Test
	public void rawByteSequenceSearch() {
		ScanSession s = first(ScanScope.RAW, ValueType.BYTES, ScanMode.EXACT, "00 00 00 64");
		assertEquals(names(s).toString(), 3, s.resultCount());
		assertEquals(0, first(ScanScope.RAW, ValueType.BYTES, ScanMode.EXACT, "DE AD BE EF").resultCount());
		// Player.stats {10,20,30,40}: the static player and the one in the party vector
		ScanSession two = first(ScanScope.RAW, ValueType.BYTES, ScanMode.EXACT, "0A 14 1E 28");
		assertEquals(2, two.resultCount());
	}

	@Test
	public void rawStringSearchAndFilter() {
		TestGame.saveData = "xxHELLO WORLDxx".getBytes();
		ScanSession s = first(ScanScope.RAW, ValueType.STRING, ScanMode.EXACT, "WORLD");
		assertEquals(1, s.resultCount());
		TestGame.saveData[8] = 'J'; // "WORLD" is now "JORLD"
		next(ScanScope.RAW, ValueType.STRING, ScanMode.CHANGED, "");
		assertEquals("the bytes at the candidate changed", 1, s.resultCount());
		next(ScanScope.RAW, ValueType.STRING, ScanMode.UNCHANGED, "");
		assertEquals(1, s.resultCount());
		next(ScanScope.RAW, ValueType.STRING, ScanMode.EQUAL_TO, "WORLD");
		assertEquals("no longer equal to the original text", 0, s.resultCount());
	}

	@Test
	public void rawUnknownInitialValueAcrossArrays() {
		ScanSession s = first(ScanScope.RAW, ValueType.INT32, ScanMode.UNKNOWN, "");
		assertTrue(s.resultCount() > 6);
		TestGame.inventory[1] = 8;
		next(ScanScope.RAW, ValueType.INT32, ScanMode.INCREASED, "");
		assertEquals(names(s).toString(), 1, s.resultCount());
	}

	@Test
	public void rawInt16AcrossByteArray() {
		TestGame.saveData = new byte[]{0, 0, 0x12, 0x34, 0, 0, 0x12, 0x34, 5};
		ScanParams p = params(ScanScope.RAW, ValueType.INT16, ScanMode.EXACT, "0x1234");
		p.alignment = 1;
		assertEquals(2, dbg.runNewScan(p, null, CancelToken.NEVER).resultCount());
	}

	// ------------------------------------------------------------ sessions and limits

	@Test
	public void multipleSessionsFilterIndependently() {
		ScanSession a = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		ScanSession b = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "5000");
		assertEquals(2, dbg.sessions().size());
		assertSame(b, dbg.activeSession());
		dbg.setActiveSession(a);
		TestGame.health = 1;
		next(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.DECREASED, "");
		assertEquals(1, a.resultCount());
		assertEquals(1, b.resultCount()); // untouched
		assertHas(b, G + "money");
		dbg.resetScan();
		assertEquals(1, dbg.sessions().size());
		assertSame(b, dbg.activeSession());
		dbg.resetScan();
		assertEquals(0, dbg.sessions().size());
	}

	@Test
	public void resetAllClearsEverySessionAtOnce() {
		first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "5000");
		first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.UNKNOWN, "");
		assertEquals(3, dbg.sessions().size());
		dbg.resetAllScans();
		assertEquals(0, dbg.sessions().size());
		assertTrue(dbg.activeSession() == null);
		dbg.resetAllScans(); // nothing left: harmless
		// a new scan works as usual afterwards
		assertEquals(1, first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100").resultCount());
		assertEquals(1, dbg.sessions().size());
	}

	@Test
	public void sessionCountIsBounded() {
		for (int i = 0; i < 12; i++) {
			first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		}
		assertTrue(dbg.sessions().size() <= 6);
	}

	@Test
	public void candidateLimitTruncatesInsteadOfExhaustingTheHeap() {
		TestGame.big = new int[50000];
		ScanParams p = params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, "");
		p.maxCandidates = 1000;
		ScanSession s = dbg.runNewScan(p, null, CancelToken.NEVER);
		assertTrue(s.isTruncated());
		assertEquals(1000, s.resultCount());
	}

	@Test
	public void cancelledScanLeavesNoSession() {
		TestGame.big = new int[100000];
		CancelToken cancel = new CancelToken();
		cancel.cancel();
		try {
			dbg.runNewScan(params(ScanScope.ARRAYS, ValueType.INT32, ScanMode.UNKNOWN, ""), null, cancel);
			fail();
		} catch (java.util.concurrent.CancellationException expected) {
			assertEquals(0, dbg.sessions().size());
		}
	}

	@Test
	public void resultPagingWorks() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		long n = s.resultCount();
		List<MemoryDebugger.ScanResult> all = dbg.results(s, 0, 1000);
		assertEquals(n, all.size());
		List<MemoryDebugger.ScanResult> tail = dbg.results(s, 2, 1000);
		assertEquals(n - 2, tail.size());
		assertEquals(all.get(2).location, tail.get(0).location);
		assertEquals(0, dbg.results(s, n, 10).size());
	}

	@Test
	public void pausesTheGameDuringScansWhenAsked() {
		ScanParams p = params(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		p.pauseDuringScan = true;
		sawPaused = false;
		dbg.runNewScan(p, null, CancelToken.NEVER);
		assertTrue("the gate was closed while the scan read the game", sawPaused);
		assertTrue("the gate is released again after the scan", !PauseGate.isPaused());

		p.pauseDuringScan = false;
		sawPaused = false;
		dbg.runNewScan(p, null, CancelToken.NEVER);
		assertTrue("not paused when the user opted out", !sawPaused);
	}
}
