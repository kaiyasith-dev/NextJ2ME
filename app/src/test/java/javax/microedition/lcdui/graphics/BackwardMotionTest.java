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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "Clean edges" searches the motion both ways: forward (old to new frame) on the new frame's
 * blocks and backward (new to old) on the old frame's blocks, by swapping the frames. Where nothing
 * is covered or uncovered, the backward motion is the forward motion turned around; next to a moving
 * object the two disagree, which is how the shader finds covered and uncovered places.
 */
public class BackwardMotionTest {
	private static double background(double x, double y) {
		return 128 + 40 * Math.sin(x * 0.21) + 35 * Math.cos(y * 0.17) + 30 * Math.sin((x + y) * 0.11);
	}

	/** Background moved by (bx, by); optionally a flat-ish object at objectX moved with it or not. */
	private static byte[] picture(int w, int h, double bx, double by, int objectX) {
		byte[] out = new byte[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				double v = background(x - bx, y - by);
				if (objectX >= 0 && x >= objectX && x < objectX + w / 4 && y >= h / 3 && y < h / 3 + h / 4) {
					v = 20 + 15 * Math.sin((x - objectX) * 0.6) * Math.cos(y * 0.5); // the object
				}
				out[y * w + x] = (byte) Math.max(0, Math.min(255, Math.round(v)));
			}
		}
		return out;
	}

	private static int[] median(MotionEstimator.Result r) {
		java.util.List<Integer> xs = new java.util.ArrayList<>();
		java.util.List<Integer> ys = new java.util.ArrayList<>();
		for (int by = 3; by < r.rows - 3; by++) {
			for (int bx = 3; bx < r.cols - 3; bx++) {
				int i = (by * r.cols + bx) * 2;
				xs.add(r.vectors[i]);
				ys.add(r.vectors[i + 1]);
			}
		}
		java.util.Collections.sort(xs);
		java.util.Collections.sort(ys);
		return new int[]{xs.get(xs.size() / 2), ys.get(ys.size() / 2)};
	}

	@Test
	public void theNormalSearchBackwardIsTheForwardMotionTurnedAround() {
		MotionEstimator e = new MotionEstimator(120, 160, 8, 8);
		byte[] prev = picture(120, 160, 0, 0, -1);
		byte[] curr = picture(120, 160, 4, -2, -1); // background moved 4, -2 half-size pixels
		int[] forward = median(e.estimate(prev, curr));
		int[] backward = median(e.estimate(curr, prev));
		assertEquals(4, forward[0]);
		assertEquals(-2, forward[1]);
		assertEquals(-forward[0], backward[0]);
		assertEquals(-forward[1], backward[1]);
	}

	@Test
	public void theFineSearchBackwardIsTheForwardMotionTurnedAround() {
		FineMotionEstimator e = new FineMotionEstimator(240, 320);
		byte[] prev = picture(240, 320, 0, 0, -1);
		byte[] curr = picture(240, 320, 6.5, 3, -1);
		int[] forward = median(e.estimate(prev, curr));
		int[] backward = median(e.estimate(curr, prev));
		assertEquals(26, forward[0], 1); // 6.5 pixels in quarter pixels
		assertEquals(12, forward[1], 1);
		assertEquals(-forward[0], backward[0], 1);
		assertEquals(-forward[1], backward[1], 1);
	}

	@Test
	public void nextToAMovingObjectTheTwoDirectionsDisagree() {
		// the background stands still, the object moves 12 pixels to the right
		FineMotionEstimator e = new FineMotionEstimator(240, 320);
		byte[] prev = picture(240, 320, 0, 0, 60);
		byte[] curr = picture(240, 320, 0, 0, 72);
		MotionEstimator.Result fwd = e.estimate(prev, curr);
		MotionEstimator.Result bwd = e.estimate(curr, prev);
		// a block of the background just behind the object (uncovered: in the old frame the object
		// was there) and its counterpart in the old frame do not tell the same story
		int row = (320 / 3 + 20) / FineMotionEstimator.BLOCK;
		int col = 62 / FineMotionEstimator.BLOCK; // uncovered area: x 60..72
		int i = (row * fwd.cols + col) * 2;
		int forwardX = fwd.vectors[i];
		int backwardX = bwd.vectors[i];
		// consistent motion would have backwardX == -forwardX; here the old frame shows the object
		// (moving), the new one the background (still), so they differ by several pixels
		assertTrue("forward " + forwardX + " backward " + backwardX,
				Math.abs(forwardX + backwardX) >= 4 * FineMotionEstimator.UNITS_PER_PIXEL);
	}
}
