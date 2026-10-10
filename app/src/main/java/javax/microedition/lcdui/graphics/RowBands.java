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

package javax.microedition.lcdui.graphics;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Splits rows of blocks into bands and works them out at the same time: all but the first band
 * on helper threads, the first on the calling thread, which then waits for the rest. The motion
 * searches use it; every block is searched on its own, so the result is the same however the rows
 * are split.
 */
final class RowBands {
	/** The work for rows {@code from} (included) to {@code to} (excluded). */
	interface Band {
		void run(int from, int to);
	}

	private RowBands() {
	}

	/**
	 * Runs {@code band} over rows 0 to {@code rows}, in up to {@code parts} bands.
	 *
	 * @param helpers threads for all bands but the first; null (or one part) runs everything here
	 * @throws CancellationException if this thread was interrupted while waiting (shutting down)
	 */
	static void run(int rows, ExecutorService helpers, int parts, Band band) {
		parts = Math.max(1, Math.min(parts, rows));
		if (helpers == null || parts == 1) {
			band.run(0, rows);
			return;
		}
		@SuppressWarnings("unchecked")
		Future<?>[] futures = new Future[parts - 1];
		for (int p = 1; p < parts; p++) {
			final int from = rows * p / parts;
			final int to = rows * (p + 1) / parts;
			futures[p - 1] = helpers.submit(() -> band.run(from, to));
		}
		RuntimeException failure = null;
		try {
			band.run(0, rows / parts);
		} catch (RuntimeException e) {
			failure = e;
		}
		for (Future<?> f : futures) {
			try {
				f.get();
			} catch (InterruptedException e) {
				for (Future<?> g : futures) {
					g.cancel(true);
				}
				Thread.currentThread().interrupt();
				throw new CancellationException("interrupted");
			} catch (ExecutionException e) {
				if (failure == null) {
					Throwable cause = e.getCause();
					failure = cause instanceof RuntimeException ? (RuntimeException) cause
							: new RuntimeException(cause);
				}
			}
		}
		if (failure != null) {
			throw failure;
		}
	}
}
