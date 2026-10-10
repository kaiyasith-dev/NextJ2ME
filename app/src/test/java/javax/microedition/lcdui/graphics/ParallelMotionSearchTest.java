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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class ParallelMotionSearchTest {
	private static ExecutorService helpers;

	@BeforeClass
	public static void startHelpers() {
		helpers = Executors.newFixedThreadPool(6);
	}

	@AfterClass
	public static void stopHelpers() {
		helpers.shutdownNow();
	}

	/** A detailed picture with an object that moves differently from the background. */
	private static byte[] picture(int w, int h, double mx, double my, double ox, double oy) {
		byte[] out = new byte[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double bx = x - mx, by = y - my;
				double v = 128 + 40 * Math.sin(bx * 0.21) + 35 * Math.cos(by * 0.17) + 30 * Math.sin((bx + by) * 0.11);
				double px = x - w / 3.0 - ox, py = y - h / 3.0 - oy;
				if (px >= 0 && px < w / 4.0 && py >= 0 && py < h / 5.0) {
					v = 30 + 20 * Math.cos(px * 0.5) * Math.sin(py * 0.4); // the object
				}
				out[y * w + x] = (byte) Math.max(0, Math.min(255, Math.round(v)));
			}
		}
		return out;
	}

	private static void assertSameResult(MotionEstimator.Result a, MotionEstimator.Result b) {
		assertEquals(a.cols, b.cols);
		assertEquals(a.rows, b.rows);
		assertArrayEquals(a.vectors, b.vectors);
		assertEquals(a.sceneCut, b.sceneCut);
		assertEquals(a.meanError, b.meanError, 0f);
	}

	@Test
	public void theNormalSearchGivesTheSameResultOnSeveralThreads() {
		MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
		byte[] prev = picture(120, 160, 0, 0, 0, 0);
		byte[] curr = picture(120, 160, 3, -2, 6, 4);
		MotionEstimator.Result alone = e.estimate(prev, curr);
		for (int parts : new int[]{2, 3, 4, 7, 50}) {
			assertSameResult(alone, e.estimate(prev, curr, helpers, parts));
		}
	}

	@Test
	public void theFineSearchGivesTheSameResultOnSeveralThreads() {
		FineMotionEstimator e = new FineMotionEstimator(240, 320);
		byte[] prev = picture(240, 320, 0, 0, 0, 0);
		byte[] curr = picture(240, 320, 5.5, -3.25, 14, 9);
		MotionEstimator.Result alone = e.estimate(prev, curr);
		for (int parts : new int[]{2, 3, 4, 7, 100}) {
			assertSameResult(alone, e.estimate(prev, curr, helpers, parts));
		}
	}

	@Test
	public void noHelpersOrOnePartRunsHere() {
		MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
		byte[] prev = picture(120, 160, 0, 0, 0, 0);
		byte[] curr = picture(120, 160, 2, 1, 0, 0);
		MotionEstimator.Result alone = e.estimate(prev, curr);
		assertSameResult(alone, e.estimate(prev, curr, null, 4));
		assertSameResult(alone, e.estimate(prev, curr, helpers, 1));
	}

	@Test
	public void everyRowIsWorkedOnExactlyOnce() {
		for (int rows : new int[]{1, 2, 5, 20, 41}) {
			for (int parts : new int[]{1, 2, 3, 4, 8, 64}) {
				AtomicIntegerArray seen = new AtomicIntegerArray(rows);
				RowBands.run(rows, helpers, parts, (from, to) -> {
					for (int r = from; r < to; r++) {
						seen.incrementAndGet(r);
					}
				});
				for (int r = 0; r < rows; r++) {
					assertEquals("rows " + rows + " parts " + parts + " row " + r, 1, seen.get(r));
				}
			}
		}
	}

	@Test
	public void aFailureInABandReachesTheCaller() {
		IllegalStateException boom = new IllegalStateException("boom");
		try {
			RowBands.run(10, helpers, 4, (from, to) -> {
				if (from > 0) {
					throw boom; // in a helper band
				}
			});
			fail();
		} catch (IllegalStateException e) {
			assertSame(boom, e);
		}
	}

	@Test
	public void beingInterruptedWhileWaitingStopsTheSearch() {
		Thread.currentThread().interrupt();
		try {
			RowBands.run(10, helpers, 4, (from, to) -> {
			});
			fail();
		} catch (CancellationException expected) {
			assertTrue(Thread.interrupted()); // the interruption is kept for the caller (and cleared here)
		}
	}

	@Test
	public void halfTheCoresAtMostFourAreUsed() {
		assertEquals(1, FrameGenerator.searchParts(1));
		assertEquals(1, FrameGenerator.searchParts(2));
		assertEquals(1, FrameGenerator.searchParts(3));
		assertEquals(2, FrameGenerator.searchParts(4));
		assertEquals(3, FrameGenerator.searchParts(6));
		assertEquals(4, FrameGenerator.searchParts(8));
		assertEquals(4, FrameGenerator.searchParts(12));
	}

	@Test
	public void howMuchFasterItIs() {
		int cores = Runtime.getRuntime().availableProcessors();
		int parts = Math.max(2, Math.min(4, cores / 2));
		for (int[] size : new int[][]{{240, 320}, {480, 800}}) {
			FineMotionEstimator e = new FineMotionEstimator(size[0], size[1]);
			byte[] prev = picture(size[0], size[1], 0, 0, 0, 0);
			byte[] curr = picture(size[0], size[1], 7, 3, 12, 5);
			for (int i = 0; i < 5; i++) { // warm up
				e.estimate(prev, curr);
				e.estimate(prev, curr, helpers, parts);
			}
			long alone = time(() -> e.estimate(prev, curr));
			long shared = time(() -> e.estimate(prev, curr, helpers, parts));
			System.out.printf("Motion HQ %dx%d: %.1f ms alone, %.1f ms on %d threads (%d cores here)%n",
					size[0], size[1], alone / 1e6, shared / 1e6, parts, cores);
		}
	}

	private static long time(Runnable r) {
		int runs = 20;
		long start = System.nanoTime();
		for (int i = 0; i < runs; i++) {
			r.run();
		}
		return (System.nanoTime() - start) / runs;
	}
}
