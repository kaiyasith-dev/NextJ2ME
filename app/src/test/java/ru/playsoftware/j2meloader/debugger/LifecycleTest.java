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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LifecycleTest extends DebuggerTestBase {

	private MemoryValue val(String text) {
		return MemoryValue.parse(ValueType.INT32, text, StringEncoding.UTF8);
	}

	private static boolean debuggerThreadsAlive() {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.isAlive() && t.getName().startsWith("MemDbg")) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------ restart

	@Test
	public void restartInvalidatesScanResultsButKeepsDurableWatches() {
		ScanSession s = first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "4242");
		MemoryLocation loc = dbg.results(s, 0, 1).get(0).location;
		MemoryWatch w = dbg.addWatch("HP", MemoryReference.staticField(TestGame.class.getName(), "health"),
				ValueType.INT32, true, StringEncoding.UTF8, 0);
		int gen = dbg.generation();
		assertNotNull(dbg.read(loc));

		// the game restarts: a new MIDlet instance with fresh state
		dbg.onMidletLoading();
		TestGame.reset();
		game = new TestGame();
		dbg.onClassLoaded(TestGame.class);

		assertEquals(gen + 1, dbg.generation());
		assertEquals(0, dbg.sessions().size());
		assertNull(dbg.activeSession());
		assertNotNull(dbg.invalidationNote());
		assertNull("object ids of the previous run are gone", dbg.read(loc));
		assertEquals("Unavailable", dbg.describe(loc));
		assertEquals("durable references still resolve", 100, dbg.read(w).bits);
		try {
			next(ScanScope.OBJECTS, ValueType.INT32, ScanMode.UNCHANGED, "");
			org.junit.Assert.fail();
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("scan"));
		}
		// ... and a scan of the new run works
		assertEquals(1, first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "4242").resultCount());
	}

	@Test
	public void restartReleasesAPausedGameAndResetsSessionReferences() {
		dbg.pauseGame();
		assertTrue(PauseGate.isPaused());
		dbg.onMidletLoading();
		assertFalse("a restart never leaves the new game parked", PauseGate.isPaused());
		assertFalse(dbg.isGamePaused());
	}

	@Test
	public void sessionFromThePreviousRunCannotBeFiltered() {
		ScanSession old = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.UNKNOWN, "");
		dbg.onMidletLoading();
		dbg.onClassLoaded(TestGame.class);
		assertNotSame(old, dbg.activeSession());
		assertTrue(old.generation != dbg.generation());
	}

	// ------------------------------------------------------------ destruction

	@Test
	public void destroyReleasesEverythingAndSavesTheGameData() throws Exception {
		dbg.setFreezePeriodMs(20);
		dbg.addWatch("HP", MemoryReference.staticField(TestGame.class.getName(), "health"),
				ValueType.INT32, true, StringEncoding.UTF8, 0);
		dbg.addFreeze("Money", MemoryReference.staticField(TestGame.class.getName(), "money"),
				ValueType.INT32, true, StringEncoding.UTF8, 0, val("1"), true);
		first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.UNKNOWN, "");
		dbg.pauseGame();
		assertTrue(dbg.isFreezeTimerRunning());

		dbg.onMidletDestroyed();

		assertTrue(dbg.isDestroyed());
		assertEquals(MemoryDebugger.MidletState.DESTROYED, dbg.midletState());
		assertFalse("the gate is open again", PauseGate.isPaused());
		assertFalse(dbg.isFreezeTimerRunning());
		assertEquals(0, dbg.sessions().size());
		assertTrue(dbg.statsText(), dbg.statsText().contains("0 objects"));
		assertTrue("per-game data was flushed", storeFile.isFile());
		assertTrue("no debugger thread outlives the game", waitFor(2000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return !debuggerThreadsAlive();
			}
		}));
		// nothing throws once the game is gone
		assertNull(dbg.read(dbg.watches().get(0)));
		dbg.pauseGame();
		assertFalse(PauseGate.isPaused());
		try {
			first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "1");
			org.junit.Assert.fail();
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("no longer running"));
		}
	}

	@Test
	public void asyncScanAfterDestroyReportsAnError() throws Exception {
		dbg.onMidletDestroyed();
		final AtomicReference<String> error = new AtomicReference<>();
		dbg.startNewScan(params(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "1"),
				new MemoryDebugger.ScanListener() {
					@Override
					public void onProgress(long regionsDone, long candidates) {
					}

					@Override
					public void onFinished(ScanSession session) {
					}

					@Override
					public void onCancelled() {
					}

					@Override
					public void onError(String message) {
						error.set(message);
					}
				});
		assertNotNull(error.get());
	}

	@Test
	public void asyncScanRunsOnItsOwnThreadAndReportsTheResult() throws Exception {
		final CountDownLatch done = new CountDownLatch(1);
		final AtomicReference<ScanSession> result = new AtomicReference<>();
		final AtomicReference<String> thread = new AtomicReference<>();
		dbg.startNewScan(params(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100"),
				new MemoryDebugger.ScanListener() {
					@Override
					public void onProgress(long regionsDone, long candidates) {
					}

					@Override
					public void onFinished(ScanSession session) {
						result.set(session);
						thread.set(Thread.currentThread().getName());
						done.countDown();
					}

					@Override
					public void onCancelled() {
						done.countDown();
					}

					@Override
					public void onError(String message) {
						done.countDown();
					}
				});
		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertNotNull(result.get());
		assertEquals("MemDbgScan", thread.get());
		assertEquals(1, result.get().resultCount());
		assertSame(result.get(), dbg.activeSession());
	}

	@Test
	public void midletStateChangesAreTrackedAndNotified() {
		final int[] changes = new int[1];
		dbg.addListener(new MemoryDebugger.Listener() {
			@Override
			public void onChanged() {
				changes[0]++;
			}
		});
		dbg.onMidletState(MemoryDebugger.MidletState.PAUSED);
		assertEquals(MemoryDebugger.MidletState.PAUSED, dbg.midletState());
		dbg.onMidletState(MemoryDebugger.MidletState.RUNNING);
		assertEquals(MemoryDebugger.MidletState.RUNNING, dbg.midletState());
		assertTrue(changes[0] >= 2);
	}

	// ------------------------------------------------------------ master switch

	@Test
	public void installAndUninstallControlTheGlobalInstance() {
		assertNull("off by default: nothing allocated", MemoryDebugger.get());
		MemoryDebugger d = MemoryDebugger.install(roots, new DebuggerStore(null, "x"));
		assertSame(d, MemoryDebugger.get());
		assertSame("install is idempotent", d, MemoryDebugger.install(roots, new DebuggerStore(null, "y")));
		MemoryDebugger.uninstall();
		assertNull(MemoryDebugger.get());
		assertTrue(d.isDestroyed());
	}

	@Test
	public void unusedDebuggerStartsNoThreads() throws Exception {
		MemoryDebugger.uninstall();
		dbg.onMidletDestroyed();
		assertTrue(waitFor(2000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return !debuggerThreadsAlive();
			}
		}));
		MemoryDebugger fresh = newDebugger();
		assertFalse("creating the debugger does not start the freeze timer", fresh.isFreezeTimerRunning());
		assertFalse(debuggerThreadsAlive());
		fresh.onMidletDestroyed();
	}

	// ------------------------------------------------------------ invalid references

	@Test
	public void collectedObjectsBecomeUnavailable() {
		TestGame.Player temp = new TestGame.Player();
		temp.hp = 31337;
		TestGame.player = temp;
		game.owner = null;
		TestGame.party.clear();
		ScanSession s = first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "31337");
		MemoryLocation loc = dbg.results(s, 0, 1).get(0).location;
		assertEquals(31337, dbg.read(loc).bits);

		temp = null;
		TestGame.player = null; // the game dropped the object
		boolean collected = false;
		for (int i = 0; i < 20 && !collected; i++) {
			System.gc();
			collected = dbg.read(loc) == null;
		}
		assumeTrue("the JVM did not collect the object in time", collected);
		assertEquals("Unavailable", dbg.describe(loc));
		try {
			dbg.write(loc, val("1"));
			org.junit.Assert.fail();
		} catch (UnavailableException expected) {
			// reported, the game is not affected
		}
		next(ScanScope.OBJECTS, ValueType.INT32, ScanMode.UNCHANGED, "");
		assertEquals("the candidate of the dead object is dropped", 0, s.resultCount());
	}

	@Test
	public void brokenPathsAreUnavailableInsteadOfThrowing() {
		MemoryReference.Step hp = MemoryReference.Step.field(P, "hp");
		String cls = TestGame.class.getName();
		MemoryReference[] refs = {
				MemoryReference.path(cls, "player", new MemoryReference.Step[]{hp}),
				MemoryReference.path("no.such.Class", "player", new MemoryReference.Step[]{hp}),
				MemoryReference.path(cls, "noSuchField", new MemoryReference.Step[]{hp}),
				MemoryReference.path(cls, "player", new MemoryReference.Step[]{MemoryReference.Step.field(P, "nope")}),
				MemoryReference.path(cls, "inventory", new MemoryReference.Step[]{MemoryReference.Step.index(99)}),
				MemoryReference.path(cls, "party", new MemoryReference.Step[]{
						MemoryReference.Step.index(7), hp}),
				MemoryReference.namedPath("missing", new MemoryReference.Step[]{hp}),
				MemoryReference.staticField(cls, "noSuchStatic"),
				MemoryReference.raw(0xDEAD0000L),
				MemoryReference.object(ScanScope.OBJECTS, 123456, 0),
		};
		TestGame.player = null;
		for (MemoryReference ref : refs) {
			MemoryLocation loc = dbg.locate(ref, ValueType.INT32, true, StringEncoding.UTF8, 0);
			if (loc != null) {
				assertNull(ref.describe(), dbg.read(loc));
			}
			MemoryWatch w = dbg.addWatch("w", ref, ValueType.INT32, true, StringEncoding.UTF8, 0);
			assertNull(ref.describe(), dbg.read(w));
			assertEquals(MemoryTarget.Status.UNAVAILABLE, w.status());
		}
	}

	@Test
	public void listChangesDuringAWalkAreTolerated() throws Exception {
		// the game keeps mutating collections while a scan walks them
		for (int i = 0; i < 2000; i++) {
			TestGame.party.add(new TestGame.Player());
		}
		final boolean[] stop = new boolean[1];
		Thread mutator = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!stop[0]) {
					TestGame.party.add(new TestGame.Player());
					if (TestGame.party.size() > 3000) {
						TestGame.party.remove(0);
					}
					TestGame.inventory = new int[6];
				}
			}
		});
		mutator.start();
		try {
			for (int i = 0; i < 5; i++) {
				ScanSession s = first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "100");
				assertTrue(s.resultCount() > 100);
				first(ScanScope.RAW, ValueType.INT32, ScanMode.UNKNOWN, "");
			}
		} finally {
			stop[0] = true;
			mutator.join();
		}
	}
}
