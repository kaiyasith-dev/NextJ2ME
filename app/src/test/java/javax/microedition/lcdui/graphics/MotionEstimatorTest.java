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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class MotionEstimatorTest {
	private static final int W = 120;
	private static final int H = 160;

	/** A noisy background with a textured bright square at (x, y). */
	private static byte[] frame(int squareX, int squareY, long seed) {
		byte[] img = new byte[W * H];
		Random bg = new Random(seed);
		for (int i = 0; i < img.length; i++) {
			img[i] = (byte) (40 + bg.nextInt(30));
		}
		Random tex = new Random(99); // the square keeps the same texture wherever it is
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 32; x++) {
				int px = squareX + x;
				int py = squareY + y;
				if (px >= 0 && px < W && py >= 0 && py < H) {
					img[py * W + px] = (byte) (150 + tex.nextInt(100));
				}
			}
		}
		return img;
	}

	private static int[] vectorAt(MotionEstimator.Result r, int blockX, int blockY) {
		int i = (blockY * r.cols + blockX) * 2;
		return new int[]{r.vectors[i], r.vectors[i + 1]};
	}

	@Test
	public void aMovingSquareGetsTheVectorOfItsMotion() {
		MotionEstimator e = new MotionEstimator(W, H, 8, 8);
		// the same background, the square moved 5 right and 3 down
		MotionEstimator.Result r = e.estimate(frame(30, 40, 1), frame(35, 43, 1));
		// a block in the middle of the new square position
		assertArrayEquals(new int[]{5, 3}, vectorAt(r, (35 + 16) / 8, (43 + 16) / 8));
		assertFalse(r.sceneCut);
	}

	@Test
	public void motionToTheLeftAndUpIsNegative() {
		MotionEstimator e = new MotionEstimator(W, H, 8, 8);
		MotionEstimator.Result r = e.estimate(frame(50, 60, 2), frame(46, 57, 2));
		assertArrayEquals(new int[]{-4, -3}, vectorAt(r, (46 + 16) / 8, (57 + 16) / 8));
	}

	@Test
	public void nothingMovedGivesZeroVectors() {
		MotionEstimator e = new MotionEstimator(W, H, 8, 8);
		byte[] a = frame(30, 40, 3);
		MotionEstimator.Result r = e.estimate(a, a.clone());
		for (int v : r.vectors) {
			assertEquals(0, v);
		}
		assertFalse(r.sceneCut);
		assertEquals(0f, r.meanError, 0f);
	}

	@Test
	public void unrelatedFramesAreASceneCut() {
		MotionEstimator e = new MotionEstimator(W, H, 8, 8);
		byte[] a = new byte[W * H];
		byte[] b = new byte[W * H];
		new Random(10).nextBytes(a);
		new Random(11).nextBytes(b);
		assertTrue(e.estimate(a, b).sceneCut);
	}

	@Test
	public void motionBeyondTheRangeIsNotInvented() {
		MotionEstimator e = new MotionEstimator(W, H, 8, 4);
		// moved 12 pixels, but the search only reaches 4: no vector may be larger than the range
		MotionEstimator.Result r = e.estimate(frame(20, 40, 4), frame(32, 40, 4));
		for (int v : r.vectors) {
			assertTrue(Math.abs(v) <= 4);
		}
	}

	@Test
	public void aSingleWrongBlockIsSmoothedAway() {
		MotionEstimator e = new MotionEstimator(W, H, 8, 8);
		MotionEstimator.Result r = e.estimate(frame(30, 40, 5), frame(35, 40, 5));
		// vectors in the middle of the square agree with their neighbours
		int[] mid = vectorAt(r, (35 + 16) / 8, (40 + 16) / 8);
		int[] next = vectorAt(r, (35 + 16) / 8 + 1, (40 + 16) / 8);
		assertArrayEquals(mid, next);
	}

	@Test
	public void theGridCoversTheWholeImage() {
		MotionEstimator e = new MotionEstimator(W, H, 8, 8);
		assertEquals(15, e.cols());
		assertEquals(20, e.rows());
		MotionEstimator odd = new MotionEstimator(123, 161, 8, 8);
		assertEquals(16, odd.cols());
		assertEquals(21, odd.rows());
		MotionEstimator.Result r = odd.estimate(new byte[123 * 161], new byte[123 * 161]);
		assertEquals(16 * 21 * 2, r.vectors.length);
	}

	@Test
	public void vectorsAreEncodedForTheTexture() {
		MotionEstimator e = new MotionEstimator(W, H, 8, 8);
		MotionEstimator.Result r = e.estimate(frame(30, 40, 6), frame(35, 43, 6));
		byte[] enc = r.encode(2);
		assertEquals(r.cols * r.rows * 2, enc.length);
		int i = ((43 + 16) / 8 * r.cols + (35 + 16) / 8) * 2;
		assertEquals(5 * 2 + 128, enc[i] & 0xFF);
		assertEquals(3 * 2 + 128, enc[i + 1] & 0xFF);
		// a zero vector is the middle of the byte range and big vectors are limited
		assertEquals(128, enc[0] & 0xFF);
		MotionEstimator.Result big = new MotionEstimator.Result(1, 1, new int[]{500, -500}, false, 0f);
		byte[] limited = big.encode(2);
		assertEquals(255, limited[0] & 0xFF);
		assertEquals(1, limited[1] & 0xFF);
	}

	@Test
	public void colourPixelsBecomeAHalfSizeGreyImage() {
		int[] px = new int[4 * 4];
		java.util.Arrays.fill(px, 0xFF000000);
		// the top-left 2x2 is white, the rest black
		px[0] = px[1] = px[4] = px[5] = 0xFFFFFFFF;
		byte[] g = MotionEstimator.toGray(px, 4, 4);
		assertEquals(4, g.length);
		assertTrue((g[0] & 0xFF) >= 250);
		assertEquals(0, g[1]);
		assertEquals(0, g[2]);
		assertEquals(0, g[3]);
	}

	@Test(expected = IllegalArgumentException.class)
	public void aTinyImageIsRefused() {
		new MotionEstimator(4, 4, 8, 8);
	}
}
