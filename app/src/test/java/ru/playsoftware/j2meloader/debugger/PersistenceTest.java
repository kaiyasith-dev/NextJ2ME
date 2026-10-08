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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.List;

public class PersistenceTest extends DebuggerTestBase {
	private static final Charset UTF8 = Charset.forName("UTF-8");

	private MemoryValue val(ValueType t, String text) {
		return MemoryValue.parse(t, text, StringEncoding.UTF8);
	}

	private MemoryReference staticRef(String field) {
		return MemoryReference.staticField(TestGame.class.getName(), field);
	}

	private static String read(File f) throws Exception {
		return new String(java.nio.file.Files.readAllBytes(f.toPath()), UTF8);
	}

	/** Simulates the next run of the same game: a brand new debugger on the same per-game file. */
	private MemoryDebugger relaunch() {
		dbg.onMidletDestroyed();
		TestGame.reset();
		game = new TestGame();
		return dbg = newDebugger();
	}

	@Test
	public void watchesFreezesAndNamesSurviveARestart() throws Exception {
		MemoryReference path = MemoryReference.path(TestGame.class.getName(), "player",
				new MemoryReference.Step[]{MemoryReference.Step.field(P, "hp")});
		dbg.addWatch("Player HP", path, ValueType.INT32, true, StringEncoding.UTF8, 0);
		dbg.addWatch("Speed", staticRef("speed"), ValueType.FLOAT, true, StringEncoding.UTF8, 0);
		dbg.addFreeze("Money lock", staticRef("money"), ValueType.INT32, true, StringEncoding.UTF8, 0,
				val(ValueType.INT32, "999999"), false);
		assertTrue(dbg.saveNow());

		MemoryDebugger again = relaunch();
		assertEquals(2, again.watches().size());
		assertEquals("Player HP", again.watches().get(0).name());
		assertEquals(G + "player.hp", again.watches().get(0).ref().describe());
		assertEquals(ValueType.FLOAT, again.watches().get(1).type());

		assertEquals(1, again.freezes().size());
		MemoryFreeze f = again.freezes().get(0);
		assertEquals("Money lock", f.name());
		assertEquals(999999, f.value().bits);
		assertFalse(f.isEnabled());
	}

	@Test
	public void savedEnabledFreezeIsAppliedByTheNextRun() throws Exception {
		dbg.setFreezePeriodMs(20);
		dbg.addFreeze("Infinite HP", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0,
				val(ValueType.INT32, "4321"), true);
		// destroying the game flushes the per-game data, like the real shutdown does
		dbg.onMidletDestroyed();

		TestGame.reset();
		game = new TestGame();
		dbg = newDebugger();
		assertTrue("the freeze is enabled again at game start", waitFor(3000, new java.util.concurrent.Callable<Boolean>() {
			@Override
			public Boolean call() {
				return TestGame.health == 4321;
			}
		}));
	}

	@Test
	public void sessionOnlyTargetsAndScanResultsAreNeverPersisted() throws Exception {
		ScanSession s = first(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "100");
		MemoryLocation loc = dbg.results(s, 0, 1).get(0).location;
		MemoryReference session = dbg.referenceFor(loc.withSlot(loc.slot));
		dbg.addWatch("durable", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		dbg.addWatch("temporary", MemoryReference.object(ScanScope.OBJECTS, 5, 0), ValueType.INT32, true,
				StringEncoding.UTF8, 0);
		dbg.addWatch("addr", MemoryReference.raw(0x1000), ValueType.INT32, true, StringEncoding.UTF8, 0);
		assertNotNull(session);
		assertTrue(dbg.saveNow());

		String json = read(storeFile);
		assertFalse(json, json.contains("temporary"));
		assertFalse(json, json.contains("\"kind\": \"RAW\""));
		assertFalse(json, json.contains("\"kind\": \"OBJECT\""));
		assertTrue(json.contains("durable"));

		MemoryDebugger again = relaunch();
		assertEquals(1, again.watches().size());
		assertEquals("no scan results come back", 0, again.sessions().size());
		assertEquals(null, again.activeSession());
	}

	@Test
	public void scanSettingsArePerGameAndRestored() throws Exception {
		ScanParams p = params(ScanScope.OBJECTS, ValueType.FLOAT, ScanMode.EXACT, "1.5");
		p.bigEndian = false;
		p.alignment = 2;
		p.encoding = StringEncoding.UTF16BE;
		dbg.runNewScan(p, null, CancelToken.NEVER);
		dbg.setFreezePeriodMs(250);
		assertTrue(dbg.saveNow());

		MemoryDebugger again = relaunch();
		ScanParams q = again.settings().scan;
		assertEquals("scan source always starts on the default", ScanScope.RAW, q.scope);
		assertEquals(ValueType.FLOAT, q.type);
		assertEquals("1.5", q.value);
		assertFalse(q.bigEndian);
		assertEquals(2, q.alignment);
		assertEquals(StringEncoding.UTF16BE, q.encoding);
		assertEquals(250, again.freezePeriodMs());
	}

	@Test
	public void differentGamesHaveSeparateFiles() throws Exception {
		File other = new File(storeFile.getParentFile(), "other-game.json");
		MemoryDebugger a = dbg;
		MemoryDebugger b = new MemoryDebugger(roots, new DebuggerStore(other, "other-game"));
		a.addWatch("A only", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		b.addWatch("B only", staticRef("money"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		assertTrue(a.saveNow());
		assertTrue(b.saveNow());
		b.onMidletDestroyed();
		assertTrue(read(storeFile).contains("A only") && !read(storeFile).contains("B only"));
		assertTrue(read(other).contains("B only") && !read(other).contains("A only"));
		assertTrue(read(other).contains("other-game"));
	}

	@Test
	public void corruptFileIsQuarantinedAndTheDebuggerStillStarts() throws Exception {
		dbg.onMidletDestroyed();
		FileOutputStream out = new FileOutputStream(storeFile);
		out.write("{ this is not json ".getBytes(UTF8));
		out.close();
		dbg = newDebugger();
		assertEquals(0, dbg.watches().size());
		assertTrue(new File(storeFile.getPath() + ".corrupt").isFile());
		dbg.addWatch("fresh", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		assertTrue(dbg.saveNow());
		assertTrue(read(storeFile).contains("fresh"));
	}

	@Test
	public void unknownEntriesAreDroppedWithoutLosingTheRest() throws Exception {
		dbg.onMidletDestroyed();
		String json = "{\"version\":1,\"watches\":["
				+ "{\"name\":\"bad type\",\"type\":\"NOPE\",\"ref\":{\"kind\":\"STATIC_FIELD\",\"rootClass\":\"a.B\",\"rootName\":\"c\"}},"
				+ "{\"name\":\"bad ref\",\"type\":\"INT32\",\"ref\":{\"kind\":\"WHATEVER\"}},"
				+ "{\"name\":\"session\",\"type\":\"INT32\",\"ref\":{\"kind\":\"RAW\",\"rootName\":\"x\"}},"
				+ "{\"name\":\"good\",\"type\":\"INT32\",\"ref\":{\"kind\":\"STATIC_FIELD\",\"rootClass\":\"a.B\",\"rootName\":\"c\"}}"
				+ "],\"settings\":{\"scope\":\"FUTURE_SCOPE\",\"type\":\"INT16\"}}";
		FileOutputStream out = new FileOutputStream(storeFile);
		out.write(json.getBytes(UTF8));
		out.close();
		dbg = newDebugger();
		assertEquals(1, dbg.watches().size());
		assertEquals("good", dbg.watches().get(0).name());
		assertEquals("unknown enum falls back to the default", ScanScope.RAW, dbg.settings().scan.scope);
		assertEquals(ValueType.INT16, dbg.settings().scan.type);
	}

	@Test
	public void saveLeavesNoTemporaryFileBehind() throws Exception {
		dbg.addWatch("x", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		assertTrue(dbg.saveNow());
		assertTrue(storeFile.isFile());
		assertFalse(new File(storeFile.getPath() + ".tmp").exists());
	}

	@Test
	public void changesAreSavedInTheBackgroundWithoutBlockingCallers() throws Exception {
		dbg.addWatch("bg", staticRef("health"), ValueType.INT32, true, StringEncoding.UTF8, 0);
		assertTrue("debounced async save reached the disk", waitFor(3000, new java.util.concurrent.Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				return storeFile.isFile() && read(storeFile).contains("\"bg\"");
			}
		}));
	}
}
