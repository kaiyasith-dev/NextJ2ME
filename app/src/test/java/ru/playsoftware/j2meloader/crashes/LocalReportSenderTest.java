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

package ru.playsoftware.j2meloader.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalReportSenderTest {
	@Test
	public void allPartsAreIncludedInOrder() {
		String text = LocalReportSender.format("App: 1.0", "java.lang.Error: boom", "MIDlet-Name: Race", "line1\nline2");
		int header = text.indexOf("App: 1.0");
		int error = text.indexOf("java.lang.Error: boom");
		int game = text.indexOf("MIDlet-Name: Race");
		int log = text.indexOf("line1\nline2");
		assertTrue(header == 0 && header < error && error < game && game < log);
		assertTrue(text.contains("= Error =") && text.contains("= Game =") && text.contains("= Log ="));
	}

	@Test
	public void missingPartsAreLeftOutWithoutTheirTitle() {
		String text = LocalReportSender.format("App: 1.0", "trace", null, "");
		assertTrue(text.contains("= Error ="));
		assertFalse(text.contains("= Game ="));
		assertFalse(text.contains("= Log ="));
	}

	@Test
	public void nothingGivesAnEmptyText() {
		assertEquals("", LocalReportSender.format(null, null, null, null));
	}
}
