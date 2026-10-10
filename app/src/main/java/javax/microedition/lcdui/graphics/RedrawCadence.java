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
 * Decides on which screen refreshes to draw so that frame generation shows a chosen number of
 * pictures per second: on a 60 Hz screen, 30 fps draws every second refresh, 45 fps three
 * refreshes out of four. A target at or above the screen's rate draws on every refresh.
 * <p>
 * Drawing is due every {@code 1 / fps}; a refresh draws when that time falls before the middle of
 * the refresh, so the pictures stay as even as the screen allows and never drift. Times are vsync
 * times in nanoseconds. Pure logic, no Android.
 */
final class RedrawCadence {
	private static final long NO_TIME = Long.MIN_VALUE;
	/** A gap longer than this between refreshes is a pause, not the screen's refresh period. */
	private static final long PAUSE = 50_000_000L;

	private final long interval;
	private long due = NO_TIME;
	private long lastVsync = NO_TIME;
	private long period;

	/** @param fps pictures per second to show (above 0) */
	RedrawCadence(int fps) {
		if (fps <= 0) {
			throw new IllegalArgumentException("fps " + fps);
		}
		this.interval = 1_000_000_000L / fps;
	}

	/** A screen refresh happens at {@code vsyncNanos}: whether to draw on it. */
	boolean onVsync(long vsyncNanos) {
		if (lastVsync != NO_TIME) {
			long gap = vsyncNanos - lastVsync;
			if (gap > 0 && gap < PAUSE) {
				// the screen's refresh period, smoothed over a few refreshes
				period = period == 0 ? gap : (period * 7 + gap) / 8;
			}
		}
		lastVsync = vsyncNanos;
		if (due == NO_TIME) {
			due = vsyncNanos + interval;
			return true;
		}
		if (vsyncNanos + period / 2 < due) {
			return false;
		}
		due += interval;
		if (due <= vsyncNanos) {
			due = vsyncNanos + interval; // after a pause: start again from here
		}
		return true;
	}

	/** Starts over, as after a pause. */
	void reset() {
		due = NO_TIME;
		lastVsync = NO_TIME;
	}
}
