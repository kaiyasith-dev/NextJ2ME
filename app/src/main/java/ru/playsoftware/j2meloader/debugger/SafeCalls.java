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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Reads game collections without risking a deadlock.
 * <p>
 * While the game is paused, one of its threads may sit at a checkpoint <em>inside</em> a
 * {@code synchronized} Vector or Hashtable. Calling {@code toArray()} on that collection from the
 * debugger would then block until the game resumes. Only reads that touch such monitors are
 * affected, so while the {@link PauseGate} is closed they run on a helper thread with a timeout
 * and are skipped when they do not finish. When the game is running they are plain calls.
 */
final class SafeCalls {
	private static final long TIMEOUT_MS = 150;
	private static ExecutorService pool;

	private SafeCalls() {
	}

	/** Elements of a {@code java.util} collection (map values), or null if unreadable. */
	static Object[] toArray(final Object collection) {
		if (!PauseGate.isPaused()) {
			return direct(collection);
		}
		return timed(new Callable<Object[]>() {
			@Override
			public Object[] call() {
				return direct(collection);
			}
		});
	}

	/** Element {@code index} of a list, or null if out of range or unreadable. */
	static Object listGet(final List<?> list, final int index) {
		if (!PauseGate.isPaused()) {
			return get(list, index);
		}
		return timed(new Callable<Object>() {
			@Override
			public Object call() {
				return get(list, index);
			}
		});
	}

	private static Object get(List<?> list, int index) {
		return index >= 0 && index < list.size() ? list.get(index) : null;
	}

	private static Object[] direct(Object c) {
		if (c instanceof Map) {
			return ((Map<?, ?>) c).values().toArray();
		}
		if (c instanceof Collection) {
			return ((Collection<?>) c).toArray();
		}
		return null;
	}

	private static synchronized ExecutorService pool() {
		if (pool == null) {
			pool = Executors.newCachedThreadPool(new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "MemDbgSafeCall");
					t.setDaemon(true);
					return t;
				}
			});
		}
		return pool;
	}

	private static <T> T timed(Callable<T> c) {
		Future<T> f = pool().submit(c);
		try {
			return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			f.cancel(true);
			return null;
		}
	}

	static synchronized void shutdown() {
		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}
	}
}
