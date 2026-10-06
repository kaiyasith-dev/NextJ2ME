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

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

public class PauseGateTest {
	private volatile boolean stop;

	@After
	public void cleanUp() {
		stop = true;
		PauseGate.releaseAll();
		PauseGate.setExemptThread(null);
	}

	private Thread startGameThread(final AtomicLong frames) {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!stop) {
					PauseGate.checkpoint(); // what Canvas.repaint / the event queue do
					frames.incrementAndGet();
					Thread.yield();
				}
			}
		}, "fake-game");
		t.setDaemon(true);
		t.start();
		return t;
	}

	private static boolean waitFor(long ms, Callable<Boolean> c) throws Exception {
		long end = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < end) {
			if (c.call()) {
				return true;
			}
			Thread.sleep(5);
		}
		return c.call();
	}

	@Test
	public void gameThreadsParkWhileHeldAndRunAfterRelease() throws Exception {
		final AtomicLong frames = new AtomicLong();
		startGameThread(frames);
		assertTrue(waitFor(2000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return frames.get() > 100;
			}
		}));
		PauseGate.hold();
		assertTrue(PauseGate.isPaused());
		assertTrue(waitFor(2000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return PauseGate.parkedThreads() == 1;
			}
		}));
		long frozen = frames.get();
		Thread.sleep(150);
		assertEquals("the game does not advance while paused", frozen, frames.get());

		PauseGate.release();
		assertFalse(PauseGate.isPaused());
		assertTrue("and continues when resumed", waitFor(2000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return frames.get() > frozen + 100;
			}
		}));
		assertEquals(0, PauseGate.parkedThreads());
	}

	@Test
	public void holdsAreCounted() {
		PauseGate.hold();
		PauseGate.hold();
		PauseGate.release();
		assertTrue("one hold is still active", PauseGate.isPaused());
		PauseGate.release();
		assertFalse(PauseGate.isPaused());
		PauseGate.release(); // surplus release is harmless
		assertFalse(PauseGate.isPaused());
		PauseGate.hold();
		PauseGate.hold();
		PauseGate.releaseAll();
		assertFalse(PauseGate.isPaused());
	}

	@Test
	public void exemptThreadNeverParks() {
		PauseGate.setExemptThread(Thread.currentThread());
		PauseGate.hold();
		PauseGate.checkpoint(); // would block forever if the UI thread were not exempt
		assertEquals(0, PauseGate.parkedThreads());
	}

	@Test
	public void interruptDoesNotDeadlockAParkedThread() throws Exception {
		PauseGate.hold();
		final boolean[] returned = new boolean[1];
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				PauseGate.checkpoint();
				returned[0] = true;
			}
		});
		t.setDaemon(true);
		t.start();
		assertTrue(waitFor(2000, new Callable<Boolean>() {
			@Override
			public Boolean call() {
				return PauseGate.parkedThreads() == 1;
			}
		}));
		t.interrupt();
		t.join(2000);
		assertTrue(returned[0]);
	}

	@Test
	public void checkpointIsCheapWhenTheGateIsOpen() {
		// what a game pays per frame when the debugger is on but idle: one volatile read
		long start = System.nanoTime();
		for (int i = 0; i < 50_000_000; i++) {
			PauseGate.checkpoint();
		}
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.println("[perf] 50M open checkpoints: " + ms + " ms");
		assertTrue("took " + ms + " ms", ms < 3000);
	}
}
