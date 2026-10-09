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

/**
 * Keeps track of how often the game finishes a frame: the smoothed time between frames, which
 * {@link FramePacer} uses to spread each blend over one game frame.
 */
public final class FrameClock {
	private static final long MS = 1_000_000L;
	private static final long MIN_INTERVAL = 4 * MS;
	private static final long MAX_INTERVAL = 250 * MS;
	/** A pause longer than this (a menu, a loading screen) does not count as the game's frame time. */
	private static final long STALL = 400 * MS;

	private long arrival;
	private long interval = 50 * MS;
	private boolean measured;

	/** A new game frame was finished at {@code nowNanos}. */
	public void onFrame(long nowNanos) {
		if (arrival != 0) {
			long gap = nowNanos - arrival;
			if (gap > 0 && gap < STALL) {
				long clamped = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, gap));
				// the first measurement is taken as it is, the rest is smoothed
				interval = measured ? (interval * 3 + clamped) / 4 : clamped;
				measured = true;
			}
		}
		arrival = nowNanos;
	}

	/** The expected time between game frames, in nanoseconds. */
	public long intervalNanos() {
		return interval;
	}
}
