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

package ru.woesss.j2me.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ru.woesss.j2me.jar.Descriptor;

public class InstallEditsTest {
	private static Map<String, String> attrs(String... kv) {
		Map<String, String> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	@Test
	public void noChangesGiveEmptyEdits() {
		Map<String, String> shown = attrs("MIDlet-Name", "Game", "MIDlet-Vendor", "Acme", "MIDlet-Version", "1.0");
		InstallEdits e = InstallEdits.diff(shown, new HashMap<>(shown));
		assertTrue(e.isEmpty());
	}

	@Test
	public void changedAddedAndRemovedAttributesAreTracked() {
		Map<String, String> shown = attrs("MIDlet-Name", "Game", "MIDlet-Vendor", "Acme",
				"MIDlet-Version", "1.0", "Old-Key", "x", "Same", "s");
		Map<String, String> edited = attrs("MIDlet-Name", "My Game", "MIDlet-Vendor", "Acme",
				"MIDlet-Version", "1.0", "New-Key", "y", "Same", "s");
		InstallEdits e = InstallEdits.diff(shown, edited);
		assertFalse(e.isEmpty());
		assertEquals("My Game", e.get("MIDlet-Name", "Game"));
		assertEquals("Acme", e.get("MIDlet-Vendor", "Acme"));
		assertEquals("y", e.get("New-Key", null));
		assertNull("removed key", e.get("Old-Key", "x"));
		assertEquals("untouched key keeps its original", "s", e.get("Same", "s"));
	}

	@Test
	public void applyOnlyTouchesWhatWasChanged() {
		Map<String, String> shown = attrs("MIDlet-Name", "Game", "A", "1", "B", "2");
		Map<String, String> edited = attrs("MIDlet-Name", "Other", "A", "1");
		InstallEdits e = InstallEdits.diff(shown, edited);
		// the real attributes also hold things the user was never shown
		Map<String, String> real = attrs("MIDlet-Name", "Game", "A", "1", "B", "2",
				"FromManifestOnly", "kept", "MIDlet-Jar-URL", "game.jar");
		e.applyTo(real);
		assertEquals("Other", real.get("MIDlet-Name"));
		assertEquals("1", real.get("A"));
		assertFalse(real.containsKey("B"));
		assertEquals("kept", real.get("FromManifestOnly"));
		assertEquals("game.jar", real.get("MIDlet-Jar-URL"));
	}

	@Test
	public void protectedAttributesAreNeverEdited() {
		Map<String, String> shown = attrs("MIDlet-Jar-URL", "game.jar", "MIDlet-Jar-Size", "10", "Manifest-Version", "1.0");
		// the user "deleted" them all from the text
		InstallEdits e = InstallEdits.diff(shown, new HashMap<String, String>());
		assertTrue(e.isEmpty());
		// and cannot change them either
		e = InstallEdits.diff(shown, attrs("MIDlet-Jar-URL", "evil.jar"));
		assertTrue(e.isEmpty());
		assertFalse(InstallEdits.format(shown, Collections.<String>emptySet()).contains("Jar"));
	}

	@Test
	public void formatAndParseRoundTrip() {
		Map<String, String> m = attrs("MIDlet-1", "Game,/icon.png,com.Game", "Nokia-UI-Enhancement", "FullScreenCanvas",
				"MIDlet-Name", "Game");
		String text = InstallEdits.format(m, Collections.singleton("MIDlet-Name"));
		assertEquals("MIDlet-1: Game,/icon.png,com.Game\nNokia-UI-Enhancement: FullScreenCanvas\n", text);
		Map<String, String> back = InstallEdits.parse(text);
		assertEquals(2, back.size());
		assertEquals("Game,/icon.png,com.Game", back.get("MIDlet-1"));
	}

	@Test
	public void parseKeepsColonsInValuesAndSkipsBlankLines() {
		Map<String, String> m = InstallEdits.parse("\nMIDlet-Info-URL: http://example.com/a:b\r\n\r\nKey:\n");
		assertEquals("http://example.com/a:b", m.get("MIDlet-Info-URL"));
		assertEquals("", m.get("Key"));
	}

	@Test
	public void parseReportsTheBadLineNumber() {
		String[] bad = {"A: 1\nno colon here", "A: 1\n: value", "A: 1\nbad key: 2", "A: 1\nA: 2"};
		for (String text : bad) {
			try {
				InstallEdits.parse(text);
				fail("expected failure for " + text);
			} catch (IllegalArgumentException e) {
				assertEquals("2", e.getMessage());
			}
		}
	}

	@Test
	public void descriptorCopyIsIndependentAndEditsApplyToIt() throws IOException {
		Descriptor d = new Descriptor("MIDlet-Name: Game\nMIDlet-Vendor: Acme\nMIDlet-Version: 1.0\n", false);
		Descriptor copy = d.copy();
		InstallEdits e = InstallEdits.diff(d.getAttrs(),
				attrs("MIDlet-Name", "Renamed", "MIDlet-Vendor", "Acme", "MIDlet-Version", "2.0"));
		e.applyTo(copy.getAttrs());
		assertEquals("Renamed", copy.getName());
		assertEquals("2.0", copy.getVersion());
		assertEquals("original is untouched", "Game", d.getName());
		assertEquals("1.0", d.getVersion());
	}

	@Test
	public void versionsAreComparedNumberByNumber() {
		assertEquals(0, Descriptor.compareVersions("1.0", "1.0.0"));
		assertEquals(1, Descriptor.compareVersions("1.10", "1.9"));
		assertEquals(-1, Descriptor.compareVersions("1.2", "1.2.1"));
		assertEquals(1, Descriptor.compareVersions("1.0", null));
	}
}
