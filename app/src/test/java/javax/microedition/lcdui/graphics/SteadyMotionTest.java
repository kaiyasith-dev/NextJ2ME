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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Steady motion": the motion found into the previous frame is offered to the search as a guess.
 */
public class SteadyMotionTest {
	/** Vertical stripes with a period of {@code period} pixels, moved by {@code mx}. */
	private static byte[] stripes(int w, int h, int period, double mx) {
		byte[] out = new byte[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double phase = ((x - mx) % period + period) % period;
				out[y * w + x] = (byte) (phase < period / 2.0 ? 40 : 200);
			}
		}
		return out;
	}

	/** A detailed picture without repeats, moved by (mx, my). */
	private static byte[] scene(int w, int h, double mx, double my) {
		byte[] out = new byte[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double bx = x - mx, by = y - my;
				double v = 128 + 40 * Math.sin(bx * 0.21) + 35 * Math.cos(by * 0.17)
						+ 30 * Math.sin((bx + by) * 0.11) + 20 * Math.cos((bx - 2 * by) * 0.07);
				out[y * w + x] = (byte) Math.max(0, Math.min(255, Math.round(v)));
			}
		}
		return out;
	}

	/** The same motion for every block (as the previous frame would have found it). */
	private static int[] everywhere(int cols, int rows, int vx, int vy) {
		int[] v = new int[cols * rows * 2];
		for (int i = 0; i < v.length; i += 2) {
			v[i] = vx;
			v[i + 1] = vy;
		}
		return v;
	}

	private static int[] middle(MotionEstimator.Result r, int edge) {
		List<Integer> xs = new ArrayList<>();
		List<Integer> ys = new ArrayList<>();
		for (int by = edge; by < r.rows - edge; by++) {
			for (int bx = edge; bx < r.cols - edge; bx++) {
				int i = (by * r.cols + bx) * 2;
				xs.add(r.vectors[i]);
				ys.add(r.vectors[i + 1]);
			}
		}
		Collections.sort(xs);
		Collections.sort(ys);
		return new int[]{xs.get(xs.size() / 2), ys.get(ys.size() / 2)};
	}

	// ------------------------------------------------------------------ the normal search

	@Test
	public void repeatingStripesFollowTheMotionTheyHad() {
		// stripes 8 pixels apart moving 6: moving 2 back fits just as well, and is shorter
		MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
		byte[] prev = stripes(120, 160, 8, 0);
		byte[] curr = stripes(120, 160, 8, 6);
		assertEquals("without a guess the short way wins", -2, middle(e.estimate(prev, curr), 2)[0]);
		int[] last = everywhere(e.cols(), e.rows(), 6, 0);
		assertEquals("with the motion they had, they keep it", 6,
				middle(e.estimate(prev, curr, null, 1, last), 2)[0]);
	}

	@Test
	public void steadyMotionBeyondTheSearchRangeIsFollowed() {
		MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
		byte[] prev = scene(120, 160, 0, 0);
		byte[] curr = scene(120, 160, 12, 0); // the search only reaches 8
		assertNotEquals(12, middle(e.estimate(prev, curr), 3)[0]);
		int[] last = everywhere(e.cols(), e.rows(), 12, 0);
		assertEquals(12, middle(e.estimate(prev, curr, null, 1, last), 3)[0]);
	}

	@Test
	public void motionThatChangedIsNotKept() {
		MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
		byte[] prev = scene(120, 160, 0, 0);
		byte[] curr = scene(120, 160, -3, 2); // it was moving right, now it goes the other way
		int[] last = everywhere(e.cols(), e.rows(), 6, 0);
		int[] m = middle(e.estimate(prev, curr, null, 1, last), 3);
		assertEquals(-3, m[0]);
		assertEquals(2, m[1]);
	}

	@Test
	public void withoutAGuessTheResultIsUnchanged() {
		MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
		byte[] prev = scene(120, 160, 0, 0);
		byte[] curr = scene(120, 160, 3, -1);
		assertArrayEquals(e.estimate(prev, curr).vectors, e.estimate(prev, curr, null, 1, null).vectors);
		int[] zero = new int[e.cols() * e.rows() * 2];
		assertArrayEquals("a guess of no motion is no guess",
				e.estimate(prev, curr).vectors, e.estimate(prev, curr, null, 1, zero).vectors);
	}

	@Test
	public void aGuessOfTheWrongSizeIsIgnored() {
		MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
		byte[] prev = scene(120, 160, 0, 0);
		byte[] curr = scene(120, 160, 3, -1);
		assertArrayEquals(e.estimate(prev, curr).vectors, e.estimate(prev, curr, null, 1, new int[6]).vectors);
	}

	@Test
	public void severalThreadsGiveTheSameResultWithAGuess() {
		ExecutorService helpers = Executors.newFixedThreadPool(3);
		try {
			MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
			byte[] prev = stripes(120, 160, 8, 0);
			byte[] curr = stripes(120, 160, 8, 6);
			int[] last = everywhere(e.cols(), e.rows(), 6, 0);
			assertArrayEquals(e.estimate(prev, curr, null, 1, last).vectors,
					e.estimate(prev, curr, helpers, 4, last).vectors);
		} finally {
			helpers.shutdownNow();
		}
	}

	// ------------------------------------------------------------------ the fine search (Motion HQ)

	@Test
	public void theFineSearchKeepsRepeatingStripesMoving() {
		// stripes 32 pixels apart moving 24: moving 8 back fits as well and is closer to standing
		FineMotionEstimator e = new FineMotionEstimator(240, 320);
		byte[] prev = stripes(240, 320, 32, 0);
		byte[] curr = stripes(240, 320, 32, 24);
		int[] last = everywhere(e.cols(), e.rows(), 24 * FineMotionEstimator.UNITS_PER_PIXEL, 0);
		int with = middle(e.estimate(prev, curr, null, 1, last), 4)[0];
		assertEquals(24 * FineMotionEstimator.UNITS_PER_PIXEL, with, 2);
	}

	@Test
	public void theFineSearchFollowsSteadyMotionBeyondItsRange() {
		FineMotionEstimator e = new FineMotionEstimator(240, 320);
		byte[] prev = scene(240, 320, 0, 0);
		byte[] curr = scene(240, 320, 40, 0); // the coarse search reaches 32
		int[] last = everywhere(e.cols(), e.rows(), 40 * FineMotionEstimator.UNITS_PER_PIXEL, 0);
		assertEquals(40 * FineMotionEstimator.UNITS_PER_PIXEL,
				middle(e.estimate(prev, curr, null, 1, last), 6)[0], 2);
	}

	@Test
	public void theFineSearchDropsMotionThatChanged() {
		FineMotionEstimator e = new FineMotionEstimator(240, 320);
		byte[] prev = scene(240, 320, 0, 0);
		byte[] curr = scene(240, 320, -5, 3);
		int[] last = everywhere(e.cols(), e.rows(), 20 * FineMotionEstimator.UNITS_PER_PIXEL, 0);
		int[] m = middle(e.estimate(prev, curr, null, 1, last), 6);
		assertEquals(-5 * FineMotionEstimator.UNITS_PER_PIXEL, m[0], 2);
		assertEquals(3 * FineMotionEstimator.UNITS_PER_PIXEL, m[1], 2);
	}
}
