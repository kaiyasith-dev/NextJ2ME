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

public class FramePacerTest {
	private static final long MS = 1_000_000L;

	@Test
	public void beforeAnyFrameNothingIsShown() {
		FramePacer p = new FramePacer(true);
		assertEquals(0, p.update(5 * MS));
		assertEquals(1f, p.blend(), 0f);
	}

	@Test
	public void theFirstFrameIsShownAsItIs() {
		FramePacer p = new FramePacer(false);
		p.onFrame(1000 * MS, false);
		assertEquals(1, p.update(1000 * MS));
	}

	@Test
	public void crossFadingBlendsEachFrameInOverOneFrameTime() {
		FramePacer p = new FramePacer(false);
		p.onFrame(1000 * MS, false);
		p.onFrame(1050 * MS, false); // a 50 ms game
		assertEquals(2, p.update(1050 * MS));
		assertEquals(0f, p.blend(), 0f);
		p.update(1075 * MS);
		assertEquals(0.5f, p.blend(), 0.01f);
		p.update(1100 * MS);
		assertEquals(1f, p.blend(), 0.01f);
		p.update(1500 * MS);
		assertEquals("it stays on the newest frame while the game waits", 1f, p.blend(), 0f);
		assertEquals(0, p.delayNanos());
	}

	/** A 50 ms game whose motion always takes 10 ms. */
	private static FramePacer steadyMotionGame(int frames) {
		FramePacer p = new FramePacer(true);
		for (int i = 0; i < frames; i++) {
			long t = (1000 + 50 * i) * MS;
			int n = p.onFrame(t, true);
			p.update(t);
			p.onMotionReady(n, t + 10 * MS);
		}
		return p;
	}

	@Test
	public void theDisplayRunsAsFarBehindAsTheMotionTakes() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		assertTrue("delay " + delay, delay >= 10 * MS && delay <= 14 * MS);
	}

	@Test
	public void aBlendRunsAtAnEvenSpeedOverAWholeFrameTime() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true);
		p.onMotionReady(n, arrival + 10 * MS);
		// until its turn the previous blend finishes
		assertEquals(n - 1, p.update(arrival + delay - MS));
		// then it starts from the start and moves evenly, taking the whole frame time
		assertEquals(n, p.update(arrival + delay));
		assertEquals(0f, p.blend(), 0.01f);
		p.update(arrival + delay + 25 * MS);
		assertEquals(0.5f, p.blend(), 0.02f);
		p.update(arrival + delay + 50 * MS);
		assertEquals(1f, p.blend(), 0.02f);
	}

	@Test
	public void thePreviousBlendReachesItsEndWhenTheNextOneTakesOver() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true);
		p.onMotionReady(n, arrival + 10 * MS);
		assertEquals(n - 1, p.update(arrival + delay - 1));
		assertEquals("no jump at the hand-over", 1f, p.blend(), 0.02f);
	}

	@Test
	public void lateMotionIsWaitedForAtTheEndOfTheBlend() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true); // this time the motion is late
		assertEquals(n - 1, p.update(arrival + delay + 5 * MS));
		assertEquals("holding the last picture of the previous blend", 1f, p.blend(), 0f);
		p.onMotionReady(n, arrival + delay + 8 * MS);
		assertEquals(n, p.update(arrival + delay + 8 * MS));
	}

	@Test
	public void motionThatNeverComesIsNotWaitedForMoreThanHalfAFrame() {
		FramePacer p = steadyMotionGame(10);
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, true);
		assertEquals(n - 1, p.update(arrival + 20 * MS));
		assertEquals(n, p.update(arrival + 25 * MS));
	}

	@Test
	public void aFrameWithoutAMotionJobIsNotWaitedFor() {
		FramePacer p = steadyMotionGame(10);
		long delay = p.delayNanos();
		long arrival = (1000 + 50 * 10) * MS;
		int n = p.onFrame(arrival, false); // the worker was still busy
		assertEquals(n, p.update(arrival + delay));
	}

	@Test
	public void theDelayIsLimitedToMostOfAFrame() {
		FramePacer p = new FramePacer(true);
		for (int i = 0; i < 10; i++) {
			long t = (1000 + 50 * i) * MS;
			int n = p.onFrame(t, true);
			p.onMotionReady(n, t + 200 * MS); // absurdly slow motion
		}
		assertTrue(p.delayNanos() <= 50 * MS * 3 / 4);
	}

	@Test
	public void aDisplayFarBehindCatchesUpSoNoKeptFrameIsReplacedWhileShown() {
		FramePacer p = steadyMotionGame(5);
		long t = 2000 * MS;
		// three frames in a burst, without a screen refresh in between
		p.onFrame(t, true);
		p.onFrame(t + MS, true);
		int n = p.onFrame(t + 2 * MS, true);
		int shown = p.update(t + 2 * MS);
		assertTrue("shown " + shown + " newest " + n, shown >= n - 1 && shown <= n);
	}

	@Test
	public void lateNewsAboutAnOldFrameIsIgnored() {
		FramePacer p = steadyMotionGame(10);
		long before = p.delayNanos();
		p.onMotionReady(1, 99_999 * MS);
		p.onMotionReady(1000, 99_999 * MS);
		assertEquals(before, p.delayNanos());
	}
}
