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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.Callable;

public class FreezeWatchTest extends DebuggerTestBase {

	private MemoryValue val(ValueType t, String text) {
		return MemoryValue.parse(t, text, StringEncoding.UTF8);
	}

	private MemoryReference staticRef(String field) {
		return MemoryReference.staticField(TestGame.class.getName(), field);
	}

	private static Callable<Boolean> health(final int expected) {
		return new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return TestGame.health == expected;
			}
		};
	}

	private static boolean timerThreadExists() {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.getName().equals("MemDbgFreeze") && t.isAlive()) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ freeze

	@Test
	public void freezeEnforcesTheValueRepeatedly() throws Exception {
		dbg.setFreezePeriodMs(20);
		MemoryFreeze f = dbg.addFreeze("HP", staticRef("health"), ValueType.INT32, true,
				StringEncoding.UTF8, 0, val(ValueType.INT32, "9999"), true);
		assertTrue(waitFor(2000, health(9999)));
		for (int i = 0; i < 3; i++) {
			TestGame.health = 1; // the game "takes damage"
			assertTrue("enforced again after change " + i, waitFor(2000, health(9999)));
		}
		assertEquals(MemoryTarget.Status.OK, f.status());
	}

	@Test
	public void freezeCanBeDisabledEditedAndRemoved() throws Exception {
		dbg.setFreezePeriodMs(20);
		MemoryFreeze f = dbg.addFreeze("HP", staticRef("health"), ValueType.INT32, true,
				StringEncoding.UTF8, 0, val(ValueType.INT32, "50"), true);
		assertTrue(waitFor(2000, health(50)));

		dbg.setFreezeEnabled(f, false);
		TestGame.health = 7;
		Thread.sleep(200);
		assertEquals("disabled freezes leave the value alone", 7, TestGame.health);
		assertTrue("no timer thread without active freezes", waitFor(1000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return !timerThreadExists();
			}
		}));

		dbg.setFreezeValue(f, val(ValueType.INT32, "75"));
		dbg.setFreezeEnabled(f, true);
		assertTrue(waitFor(2000, health(75)));

		dbg.removeFreeze(f);
		TestGame.health = 3;
		Thread.sleep(200);
		assertEquals(3, TestGame.health);
		assertEquals(0, dbg.freezes().size());
	}

	@Test
	public void multipleFreezesRunOnOneTimer() throws Exception {
		dbg.setFreezePeriodMs(20);
		dbg.addFreeze("HP", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0,
				val(ValueType.INT32, "1000"), true);
		dbg.addFreeze("Money", staticRef("money"), ValueType.INT32, true, StringEncoding.UTF8, 0,
				val(ValueType.INT32, "123456"), true);
		dbg.addFreeze("Speed", staticRef("speed"), ValueType.FLOAT, true, StringEncoding.UTF8, 0,
				val(ValueType.FLOAT, "5.5"), true);
		dbg.addFreeze("God", staticRef("godMode"), ValueType.BOOLEAN, true, StringEncoding.UTF8, 0,
				val(ValueType.BOOLEAN, "true"), true);
		assertTrue(waitFor(3000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return TestGame.health == 1000 && TestGame.money == 123456 && TestGame.speed == 5.5f
						&& TestGame.godMode;
			}
		}));
		int timers = 0;
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.getName().equals("MemDbgFreeze") && t.isAlive()) {
				timers++;
			}
		}
		assertEquals("a single timer thread serves all freezes", 1, timers);
	}

	@Test
	public void freezeDoesNotWriteWhileTheGateIsClosed() throws Exception {
		dbg.setFreezePeriodMs(20);
		dbg.addFreeze("HP", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0,
				val(ValueType.INT32, "500"), true);
		assertTrue(waitFor(2000, health(500)));
		PauseGate.hold();
		dbg.awaitFreezeTick(); // a tick that was already running finishes first
		TestGame.health = 12;
		Thread.sleep(200);
		assertEquals("no enforcement while the game is held", 12, TestGame.health);
		PauseGate.release();
		assertTrue("enforcement resumes when the game runs again", waitFor(2000, health(500)));
	}

	@Test
	public void freezeOfAnUnavailableTargetIsReportedNotThrown() throws Exception {
		dbg.setFreezePeriodMs(20);
		MemoryFreeze f = dbg.addFreeze("Ghost", MemoryReference.staticField("no.such.Class", "x"),
				ValueType.INT32, true, StringEncoding.UTF8, 0, val(ValueType.INT32, "1"), true);
		assertTrue(waitFor(2000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return f.status() == MemoryTarget.Status.UNAVAILABLE;
			}
		}));
	}

	// ------------------------------------------------------------ references / watches

	@Test
	public void pathReferenceSurvivesObjectReplacement() {
		ScanSession s = first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "100");
		MemoryLocation loc = null;
		for (MemoryDebugger.ScanResult r : dbg.results(s, 0, 100)) {
			String d = dbg.describe(r.location);
			if (d.contains("Player") && d.endsWith(".hp")) {
				MemoryReference ref = dbg.referenceFor(r.location);
				if (ref.kind == MemoryReference.Kind.PATH && ref.describe().equals(G + "player.hp")) {
					loc = r.location;
					break;
				}
			}
		}
		assertNotNull("static player.hp must be reachable through a durable path", loc);
		MemoryReference ref = dbg.referenceFor(loc);
		assertTrue(ref.isPersistent());
		assertEquals(G + "player.hp", ref.describe());

		MemoryWatch w = dbg.addWatch("HP", loc, ref);
		assertEquals(100, dbg.read(w).bits);
		TestGame.player = new TestGame.Player(); // the game replaces the object
		TestGame.player.hp = 55;
		assertEquals("the watch follows the path to the new object", 55, dbg.read(w).bits);
		dbg.write(w, val(ValueType.INT32, "99"));
		assertEquals(99, TestGame.player.hp);
	}

	@Test
	public void arrayElementReferenceThroughStaticRoot() {
		ScanSession s = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "42");
		MemoryLocation loc = dbg.results(s, 0, 1).get(0).location;
		MemoryReference ref = dbg.referenceFor(loc);
		assertEquals(G + "inventory[3]", ref.describe());
		TestGame.inventory = new int[]{0, 0, 0, 5};
		MemoryLocation again = dbg.locate(ref, ValueType.INT32, true, StringEncoding.UTF8, 0);
		assertEquals(5, dbg.read(again).bits);
	}

	@Test
	public void staticFieldReferenceAndRawIndexReference() {
		MemoryLocation hp = find(first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100"),
				G + "health").location;
		assertEquals(G + "health", dbg.referenceFor(hp).describe());

		// a raw hit on an aligned int element also gets a durable path
		ScanSession raw = first(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "42");
		MemoryReference ref = dbg.referenceFor(dbg.results(raw, 0, 1).get(0).location);
		assertEquals(G + "inventory[3]", ref.describe());

		// unaligned or oddly typed raw hits can only be session references
		ScanParams p = params(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "100");
		p.alignment = 1;
		ScanSession all = dbg.runNewScan(p, null, CancelToken.NEVER);
		boolean sawSessionOnly = false;
		for (MemoryDebugger.ScanResult r : dbg.results(all, 0, 10)) {
			MemoryReference rr = dbg.referenceFor(r.location);
			if (!rr.isPersistent()) {
				assertEquals(MemoryReference.Kind.RAW, rr.kind);
				sawSessionOnly = true;
			}
		}
		assertTrue(sawSessionOnly);
	}

	@Test
	public void watchesCanBeRenamedRetypedAndRemoved() {
		MemoryWatch w = dbg.addWatch("", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		assertEquals(G + "health", w.name()); // default name is the reference
		dbg.renameWatch(w, "HP");
		assertEquals("HP", w.name());
		dbg.retypeWatch(w, ValueType.FLOAT, 0); // same width: reinterpretation is allowed
		assertEquals(Float.floatToRawIntBits(Float.intBitsToFloat(100)) & 0xFFFFFFFFL, dbg.read(w).bits);
		dbg.retypeWatch(w, ValueType.INT64, 0); // different width: unavailable, not garbage
		assertNull(dbg.read(w));
		assertEquals(MemoryTarget.Status.UNAVAILABLE, w.status());
		dbg.retypeWatch(w, ValueType.INT32, 0);
		assertEquals(100, dbg.read(w).bits);
		assertEquals(1, dbg.watches().size());
		dbg.removeWatch(w);
		assertEquals(0, dbg.watches().size());
	}

	@Test
	public void watchValuesRefreshWithoutAnyScan() {
		MemoryWatch hp = dbg.addWatch("HP", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		MemoryWatch lvl = dbg.addWatch("Level", staticRef("level"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		TestGame.health = 64;
		TestGame.level = 9;
		assertEquals(64, dbg.read(hp).bits);
		assertEquals(9, dbg.read(lvl).bits);
		assertEquals(MemoryTarget.Status.OK, hp.status());
	}

	// ------------------------------------------------------------ freezing a target that is not there yet

	@Test
	public void freezeWaitsForItsTargetAndAppliesWhenItAppears() throws Exception {
		dbg.setFreezePeriodMs(20);
		MemoryReference ref = MemoryReference.path(TestGame.class.getName(), "player",
				new MemoryReference.Step[]{MemoryReference.Step.field(P, "hp")});
		TestGame.player = null; // e.g. still on the title screen
		MemoryFreeze c = dbg.addFreeze("HP", ref, ValueType.INT32, true, StringEncoding.UTF8, 0,
				val(ValueType.INT32, "777"), true);
		Thread.sleep(150);
		assertEquals(MemoryTarget.Status.UNAVAILABLE, c.status());
		TestGame.player = new TestGame.Player();
		assertTrue("applied as soon as the player exists", waitFor(3000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return TestGame.player != null && TestGame.player.hp == 777;
			}
		}));
	}

	@Test
	public void freezeTimerOnlyExistsWhileNeeded() throws Exception {
		assertFalse(dbg.isFreezeTimerRunning());
		MemoryFreeze f = dbg.addFreeze("HP", staticRef("health"), ValueType.INT32, true,
				StringEncoding.UTF8, 0, val(ValueType.INT32, "5"), true);
		assertTrue(dbg.isFreezeTimerRunning());
		dbg.removeFreeze(f);
		assertFalse(dbg.isFreezeTimerRunning());
		try {
			dbg.write(f, val(ValueType.INT32, "1"));
			// removed freezes still resolve their reference: the write itself is fine
		} catch (UnavailableException e) {
			fail("a removed freeze entry must not break writes");
		}
	}
}
