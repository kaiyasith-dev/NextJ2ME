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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Drives value freezing from one low priority timer thread.
 * <p>
 * The thread exists only while at least one freeze needs enforcing, ticks at a fixed
 * low rate (default 10 Hz) and does nothing but compare and, when needed, write a few values.
 * It is never started while the debugger is unused, and is shut down as soon as the last freeze
 * is removed or the game is destroyed.
 */
final class FreezeEngine {
	static final int MIN_PERIOD_MS = 20;
	static final int MAX_PERIOD_MS = 2000;

	private final MemoryDebugger debugger;
	private ScheduledExecutorService executor;
	private ScheduledFuture<?> task;
	private int periodMs = 100;

	FreezeEngine(MemoryDebugger debugger) {
		this.debugger = debugger;
	}

	/** Starts or stops the timer depending on whether there is anything to enforce. */
	synchronized void refresh() {
		boolean needed = !debugger.isDestroyed() && debugger.activeFreezeCount() > 0;
		if (!needed) {
			stop();
			return;
		}
		if (executor == null) {
			executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "MemDbgFreeze");
					t.setDaemon(true);
					t.setPriority(Thread.NORM_PRIORITY - 1);
					return t;
				}
			});
		}
		if (task == null) {
			schedule();
		}
	}

	private void schedule() {
		task = executor.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				try {
					debugger.enforceFrozen();
				} catch (RuntimeException e) {
					// one bad tick must never end the timer
				}
			}
		}, periodMs, periodMs, TimeUnit.MILLISECONDS);
	}

	synchronized void setPeriod(int ms) {
		periodMs = Math.max(MIN_PERIOD_MS, Math.min(MAX_PERIOD_MS, ms));
		if (task != null) {
			task.cancel(false);
			schedule();
		}
	}

	/** Enforces once, right now (for example when the game resumes). */
	synchronized void kick() {
		if (executor != null && !executor.isShutdown()) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						debugger.enforceFrozen();
					} catch (RuntimeException e) {
						// ignore, see schedule()
					}
				}
			});
		}
	}

	private void stop() {
		if (task != null) {
			task.cancel(false);
			task = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	synchronized void shutdown() {
		stop();
	}

	synchronized boolean isRunning() {
		return executor != null;
	}
}
