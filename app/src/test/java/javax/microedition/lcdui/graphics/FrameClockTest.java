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

public class FrameClockTest {
	private static final long MS = 1_000_000L;

	@Test
	public void theFrameTimeFollowsTheGameAndIsSmoothed() {
		FrameClock c = new FrameClock();
		long t = 0;
		for (int i = 0; i < 20; i++) {
			t += 40 * MS;
			c.onFrame(t);
		}
		assertEquals(40 * MS, c.intervalNanos(), 1 * MS);
		t += 80 * MS; // one slow frame
		c.onFrame(t);
		assertTrue("one slow frame moves it only part of the way", c.intervalNanos() < 65 * MS);
		assertTrue(c.intervalNanos() > 40 * MS);
	}

	@Test
	public void aLongPauseDoesNotBecomeTheFrameTime() {
		FrameClock c = new FrameClock();
		long t = 0;
		for (int i = 0; i < 10; i++) {
			t += 30 * MS;
			c.onFrame(t);
		}
		long before = c.intervalNanos();
		t += 3000 * MS; // a loading screen
		c.onFrame(t);
		assertEquals(before, c.intervalNanos());
	}

	@Test
	public void absurdlyShortAndLongGapsAreLimited() {
		FrameClock fast = new FrameClock();
		fast.onFrame(1000 * MS);
		fast.onFrame(1000 * MS + 1000); // a microsecond later
		assertTrue(fast.intervalNanos() >= 4 * MS);
		FrameClock slow = new FrameClock();
		slow.onFrame(1000 * MS);
		slow.onFrame(1390 * MS);
		assertTrue(slow.intervalNanos() <= 250 * MS);
	}

}
