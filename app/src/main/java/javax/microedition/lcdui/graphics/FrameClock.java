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
 * Keeps track of how often the game finishes a frame and tells, at any moment, how far the screen
 * should be between the previous and the newest game frame (0 = still the previous one, 1 = the
 * newest). Frame generation shows that blend while it waits for the next game frame.
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

	/**
	 * How far to blend from the previous to the newest game frame at {@code nowNanos}:
	 * 0 right when a frame arrives, growing to 1 after one expected frame time, then staying at 1.
	 */
	public float phase(long nowNanos) {
		if (arrival == 0) {
			return 1f;
		}
		float p = (float) (nowNanos - arrival) / interval;
		return p < 0f ? 0f : Math.min(p, 1f);
	}

	/** The expected time between game frames, in nanoseconds. */
	public long intervalNanos() {
		return interval;
	}
}
