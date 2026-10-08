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

import java.util.Arrays;

/**
 * Finds how the picture moved between two game frames, block by block, for frame generation.
 * <p>
 * The pictures are small grey images (the game frame at half size). Every block of the new frame
 * is searched for in the previous frame within a small range; the result is a motion vector per
 * block. Plain Java so it can be tested without a screen.
 */
public final class MotionEstimator {
	/** Mean difference per pixel (0..255) above which two frames are treated as a scene cut. */
	static final float SCENE_CUT_ERROR = 24f;
	/** A block moves only if that makes it at least this much (fraction) better than staying put. */
	private static final float GAIN = 0.90f;
	/** Cost added per pixel of displacement, so that ties go to the smaller motion. */
	private static final int PENALTY_PER_PIXEL = 4;

	/** The motion of every block of a frame. */
	public static final class Result {
		public final int cols;
		public final int rows;
		/** For each block {@code dx, dy}: how far the picture moved from the previous to the new frame. */
		public final int[] vectors;
		/** True if the frames have nothing in common (a different scene): do not interpolate. */
		public final boolean sceneCut;
		/** Mean difference per pixel left after the motion was applied. */
		public final float meanError;

		Result(int cols, int rows, int[] vectors, boolean sceneCut, float meanError) {
			this.cols = cols;
			this.rows = rows;
			this.vectors = vectors;
			this.sceneCut = sceneCut;
			this.meanError = meanError;
		}

		/**
		 * The vectors as bytes for a two-channel texture: {@code dx * scale + 128} and
		 * {@code dy * scale + 128}, limited to 1..255, so the shader can read them back.
		 */
		public byte[] encode(int scale) {
			byte[] out = new byte[cols * rows * 2];
			for (int i = 0; i < vectors.length; i++) {
				int v = Math.max(-127, Math.min(127, vectors[i] * scale)) + 128;
				out[i] = (byte) v;
			}
			return out;
		}
	}

	private final int width;
	private final int height;
	private final int block;
	private final int range;
	private final int cols;
	private final int rows;

	/**
	 * @param width  width of the grey images
	 * @param height height of the grey images
	 * @param block  side of a block in pixels (8 is a good value)
	 * @param range  how far a block may move in either direction, in pixels
	 */
	public MotionEstimator(int width, int height, int block, int range) {
		if (width < block || height < block || block < 2 || range < 1) {
			throw new IllegalArgumentException("image too small for the block size");
		}
		this.width = width;
		this.height = height;
		this.block = block;
		this.range = range;
		this.cols = (width + block - 1) / block;
		this.rows = (height + block - 1) / block;
	}

	public int cols() {
		return cols;
	}

	public int rows() {
		return rows;
	}

	/** Grey image at half size (2x2 pixels averaged) from ARGB pixels. */
	public static byte[] toGray(int[] argb, int width, int height) {
		int gw = width / 2;
		int gh = height / 2;
		byte[] out = new byte[gw * gh];
		for (int y = 0; y < gh; y++) {
			int row0 = (2 * y) * width;
			int row1 = row0 + width;
			for (int x = 0; x < gw; x++) {
				int i = 2 * x;
				int sum = gray(argb[row0 + i]) + gray(argb[row0 + i + 1])
						+ gray(argb[row1 + i]) + gray(argb[row1 + i + 1]);
				out[y * gw + x] = (byte) (sum >> 2);
			}
		}
		return out;
	}

	private static int gray(int p) {
		return (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 151 + (p & 0xFF) * 28) >> 8;
	}

	/** The motion from {@code prev} to {@code curr}; both are {@code width x height} grey images. */
	public Result estimate(byte[] prev, byte[] curr) {
		int[] vectors = new int[cols * rows * 2];
		long totalBest = 0;
		long totalZero = 0;
		for (int by = 0; by < rows; by++) {
			for (int bx = 0; bx < cols; bx++) {
				int x0 = bx * block;
				int y0 = by * block;
				int x1 = Math.min(x0 + block, width);
				int y1 = Math.min(y0 + block, height);
				int zero = sad(prev, curr, x0, y0, x1, y1, 0, 0, Integer.MAX_VALUE);
				int best = zero;
				int bestDx = 0;
				int bestDy = 0;
				if (zero > 0) {
					int bestCost = zero;
					int minDx = Math.max(-range, -x0);
					int maxDx = Math.min(range, width - x1);
					int minDy = Math.max(-range, -y0);
					int maxDy = Math.min(range, height - y1);
					int limit = (int) (zero * GAIN);
					for (int dy = minDy; dy <= maxDy; dy++) {
						for (int dx = minDx; dx <= maxDx; dx++) {
							if (dx == 0 && dy == 0) {
								continue;
							}
							int penalty = PENALTY_PER_PIXEL * (Math.abs(dx) + Math.abs(dy));
							int cap = Math.min(bestCost, limit) - penalty;
							if (cap <= 0) {
								continue;
							}
							int s = sad(prev, curr, x0, y0, x1, y1, dx, dy, cap);
							if (s < cap && s + penalty < bestCost) {
								bestCost = s + penalty;
								best = s;
								bestDx = dx;
								bestDy = dy;
							}
						}
					}
				}
				totalBest += best;
				totalZero += zero;
				int i = (by * cols + bx) * 2;
				// curr(x) matches prev(x + d), so the picture moved by -d
				vectors[i] = -bestDx;
				vectors[i + 1] = -bestDy;
			}
		}
		float pixels = (float) width * height;
		float meanError = totalBest / pixels;
		boolean sceneCut = meanError > SCENE_CUT_ERROR;
		if (totalZero / pixels < 1.0f) {
			Arrays.fill(vectors, 0); // nothing moved
		} else {
			median(vectors);
		}
		return new Result(cols, rows, vectors, sceneCut, meanError);
	}

	/**
	 * Sum of absolute differences between the block of {@code curr} and the block of {@code prev}
	 * shifted by (dx, dy); stops early once it reaches {@code cap}.
	 */
	private int sad(byte[] prev, byte[] curr, int x0, int y0, int x1, int y1, int dx, int dy, int cap) {
		int sum = 0;
		for (int y = y0; y < y1; y++) {
			int c = y * width + x0;
			int p = (y + dy) * width + x0 + dx;
			for (int x = x0; x < x1; x++, c++, p++) {
				int d = (curr[c] & 0xFF) - (prev[p] & 0xFF);
				sum += d < 0 ? -d : d;
			}
			if (sum >= cap) {
				return sum;
			}
		}
		return sum;
	}

	/** 3x3 median of each component, so a single wrong block does not tear the picture. */
	private void median(int[] vectors) {
		int[] src = vectors.clone();
		int[] window = new int[9];
		for (int by = 0; by < rows; by++) {
			for (int bx = 0; bx < cols; bx++) {
				for (int c = 0; c < 2; c++) {
					int n = 0;
					for (int oy = -1; oy <= 1; oy++) {
						for (int ox = -1; ox <= 1; ox++) {
							int x = bx + ox;
							int y = by + oy;
							if (x >= 0 && x < cols && y >= 0 && y < rows) {
								window[n++] = src[(y * cols + x) * 2 + c];
							}
						}
					}
					Arrays.sort(window, 0, n);
					vectors[(by * cols + bx) * 2 + c] = window[n / 2];
				}
			}
		}
	}
}
