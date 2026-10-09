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
 * Decides which two game frames to blend on a screen refresh, and how far.
 * <p>
 * Frame k is shown blended from frame k-1, starting when frame k arrived and taking one game frame
 * time, so things move at an even speed. With motion, the moved pictures can only be drawn once
 * the motion into frame k is known, which takes the worker a few milliseconds. Rather than stand
 * still until then (which makes the motion stop and go), the whole display runs that much behind
 * the game: each blend starts a fixed delay after its frame arrived, the delay being how long the
 * motion usually takes. While frame k waits for its turn, the blend into frame k-1 finishes, so up
 * to three frames are in use (k-2, k-1 and k).
 * <p>
 * Frames are numbered from 1; times are {@code System.nanoTime()} values. Pure logic, no GL.
 */
final class FramePacer {
	/** Frames kept: the two being blended and the newest one waiting for its turn. */
	static final int KEPT = 3;
	private static final long MARGIN = 2_000_000L;

	private final FrameClock clock = new FrameClock();
	private final boolean followsMotion;
	private final long[] arrival = new long[KEPT];
	private final boolean[] ready = new boolean[KEPT];
	private int newest;
	private int shown;
	private long latency;
	private boolean measured;
	private float blend = 1f;

	/**
	 * @param followsMotion whether blends wait for the motion between frames (otherwise frames are
	 *                      only cross-faded and nothing is waited for)
	 */
	FramePacer(boolean followsMotion) {
		this.followsMotion = followsMotion;
	}

	/**
	 * A new game frame arrived.
	 *
	 * @param motionComing whether the motion into it is being worked out (then its blend waits for
	 *                     {@link #onMotionReady})
	 * @return the number of the new frame
	 */
	int onFrame(long nowNanos, boolean motionComing) {
		clock.onFrame(nowNanos);
		newest++;
		arrival[newest % KEPT] = nowNanos;
		ready[newest % KEPT] = !(followsMotion && motionComing);
		if (shown == 0) {
			shown = newest; // the very first frame is shown as it is
		} else if (shown < newest - 1) {
			// the display is more than a frame behind (the game sent frames very fast): the oldest
			// kept frame is about to be replaced, so move on
			shown = newest - 1;
		}
		return newest;
	}

	/** The worker finished with frame {@code frame} at {@code readyNanos}, with or without motion. */
	void onMotionReady(int frame, long readyNanos) {
		if (frame < 1 || frame > newest || frame <= newest - KEPT || ready[frame % KEPT]) {
			return;
		}
		ready[frame % KEPT] = true;
		long took = readyNanos - arrival[frame % KEPT];
		if (took < 0) {
			return;
		}
		took = Math.min(took, clock.intervalNanos());
		latency = measured ? (latency * 3 + took) / 4 : took;
		measured = true;
	}

	/** How long after a frame arrived its blend starts. */
	long delayNanos() {
		if (!followsMotion || !measured) {
			return 0;
		}
		return Math.min(latency + MARGIN, clock.intervalNanos() * 3 / 4);
	}

	/**
	 * Works out what to show at {@code nowNanos}.
	 *
	 * @return the frame k to show, blended from frame k-1 by {@link #blend()}; 0 before any frame
	 */
	int update(long nowNanos) {
		if (newest == 0) {
			blend = 1f;
			return 0;
		}
		long interval = clock.intervalNanos();
		long delay = delayNanos();
		while (shown < newest) {
			int next = (shown + 1) % KEPT;
			if (nowNanos < arrival[next] + delay) {
				break;
			}
			// the motion is later than usual: wait a little more for it, but not forever
			if (!ready[next] && nowNanos - arrival[next] < interval / 2) {
				break;
			}
			shown++;
		}
		float p = (float) (nowNanos - arrival[shown % KEPT] - delay) / interval;
		blend = p < 0f ? 0f : Math.min(p, 1f);
		return shown;
	}

	/** How far frame k-1 is blended into frame k (0 to 1), as of the last {@link #update}. */
	float blend() {
		return blend;
	}

	/** The expected time between game frames, in nanoseconds. */
	long intervalNanos() {
		return clock.intervalNanos();
	}
}
