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

import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared setup: a debugger attached to a {@link TestGame} with a temporary per-game store. */
public abstract class DebuggerTestBase {
	protected static final String G = TestGame.class.getName() + ".";
	protected static final String P = TestGame.Player.class.getName();

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	protected TestGame game;
	protected File storeFile;
	protected MemoryDebugger dbg;

	protected final RootSource roots = new RootSource() {
		@Override
		public Map<String, Object> namedRoots() {
			Map<String, Object> m = new HashMap<>();
			if (game != null) {
				m.put("midlet", game);
			}
			return m;
		}

		@Override
		public boolean isAppClass(Class<?> c) {
			return c.getName().startsWith(TestGame.class.getName());
		}
	};

	@Before
	public void setUpDebugger() throws Exception {
		TestGame.reset();
		game = new TestGame();
		storeFile = new File(tmp.newFolder("debugger"), "game.json");
		dbg = newDebugger();
	}

	@After
	public void tearDownDebugger() {
		if (dbg != null) {
			dbg.onMidletDestroyed();
		}
		TestGame.reset();
	}

	protected MemoryDebugger newDebugger() {
		MemoryDebugger d = new MemoryDebugger(roots, new DebuggerStore(storeFile, "test-game"));
		d.onMidletLoading();
		d.onClassLoaded(TestGame.class);
		d.onClassLoaded(TestGame.Player.class);
		d.onMidletState(MemoryDebugger.MidletState.RUNNING);
		return d;
	}

	protected static ScanParams params(ScanScope scope, ValueType type, ScanMode mode, String value) {
		ScanParams p = new ScanParams();
		p.scope = scope;
		p.type = type;
		p.mode = mode;
		p.value = value;
		return p;
	}

	protected ScanSession first(ScanScope scope, ValueType type, ScanMode mode, String value) {
		return dbg.runNewScan(params(scope, type, mode, value), null, CancelToken.NEVER);
	}

	protected ScanSession next(ScanScope scope, ValueType type, ScanMode mode, String value) {
		return dbg.runNextScan(params(scope, type, mode, value), null, CancelToken.NEVER);
	}

	/** Descriptions ({@code describe}) of all results of a session. */
	protected List<String> names(ScanSession s) {
		List<String> out = new ArrayList<>();
		for (MemoryDebugger.ScanResult r : dbg.results(s, 0, 100000)) {
			out.add(dbg.describe(r.location));
		}
		return out;
	}

	protected MemoryDebugger.ScanResult find(ScanSession s, String describeContains) {
		for (MemoryDebugger.ScanResult r : dbg.results(s, 0, 100000)) {
			if (dbg.describe(r.location).contains(describeContains)) {
				return r;
			}
		}
		throw new AssertionError("No result containing '" + describeContains + "' in " + names(s));
	}

	protected void assertHas(ScanSession s, String describeContains) {
		find(s, describeContains);
	}

	protected void assertHasNot(ScanSession s, String describeContains) {
		for (String n : names(s)) {
			assertTrue("Unexpected result " + n, !n.contains(describeContains));
		}
	}

	/** Polls until the condition holds or the timeout expires. */
	protected static boolean waitFor(long timeoutMs, java.util.concurrent.Callable<Boolean> cond) throws Exception {
		long end = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < end) {
			if (cond.call()) {
				return true;
			}
			Thread.sleep(10);
		}
		return cond.call();
	}
}
