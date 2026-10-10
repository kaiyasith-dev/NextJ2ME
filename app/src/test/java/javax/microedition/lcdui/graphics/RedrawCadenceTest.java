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

import java.util.Random;

public class RedrawCadenceTest {
	/** How many of {@code refreshes} vsyncs at {@code hz} draw for a target of {@code fps}. */
	private static String pattern(int hz, int fps, int refreshes) {
		RedrawCadence c = new RedrawCadence(fps);
		StringBuilder sb = new StringBuilder();
		double period = 1e9 / hz;
		for (int i = 0; i < refreshes; i++) {
			sb.append(c.onVsync(1_000_000_000L + Math.round(i * period)) ? 'x' : '.');
		}
		return sb.toString();
	}

	private static int count(String s) {
		int n = 0;
		for (char ch : s.toCharArray()) {
			n += ch == 'x' ? 1 : 0;
		}
		return n;
	}

	@Test
	public void halfTheRefreshRateDrawsEverySecondRefresh() {
		String s = pattern(60, 30, 61);
		assertTrue(s, s.substring(2).startsWith("x.x.x.x.x.x."));
		assertEquals(31, count(s), 1);
		s = pattern(120, 60, 121);
		assertTrue(s, s.substring(2).startsWith("x.x.x.x.x.x."));
		assertEquals(61, count(s), 1);
	}

	@Test
	public void aRateBetweenDividersDrawsAnEvenMix() {
		String s = pattern(60, 45, 600); // ten seconds
		assertEquals(450, count(s), 2);
		assertTrue("never two refreshes in a row without a picture: " + s, !s.contains(".."));
	}

	@Test
	public void theScreenRateOrMoreDrawsEveryRefresh() {
		assertEquals(120, count(pattern(60, 60, 120)));
		assertEquals(120, count(pattern(60, 120, 120)));
		assertEquals(120, count(pattern(90, 90, 120)));
	}

	@Test
	public void aThirdOfThe90HzRefreshIs30Fps() {
		String s = pattern(90, 30, 90);
		assertEquals(30, count(s), 1);
		assertTrue(s, s.substring(3).startsWith("x..x..x..x.."));
	}

	@Test
	public void vsyncJitterDoesNotChangeTheRate() {
		RedrawCadence c = new RedrawCadence(30);
		Random r = new Random(3);
		int drawn = 0;
		for (int i = 0; i < 600; i++) { // ten seconds at 60 Hz, with up to 1.5 ms jitter
			long t = 1_000_000_000L + Math.round(i * 1e9 / 60) + r.nextInt(3_000_000) - 1_500_000;
			drawn += c.onVsync(t) ? 1 : 0;
		}
		assertEquals(300, drawn, 3);
	}

	@Test
	public void afterAPauseItStartsAgainWithoutCatchingUp() {
		RedrawCadence c = new RedrawCadence(30);
		long t = 1_000_000_000L;
		for (int i = 0; i < 60; i++) {
			c.onVsync(t + i * 16_666_667L);
		}
		long later = t + 5_000_000_000L; // five seconds without refreshes
		assertTrue(c.onVsync(later));
		assertTrue("no burst of pictures to catch up", !c.onVsync(later + 16_666_667L));
		assertTrue(c.onVsync(later + 33_333_333L));
	}

	@Test(expected = IllegalArgumentException.class)
	public void aTargetMustBePositive() {
		new RedrawCadence(0);
	}
}
