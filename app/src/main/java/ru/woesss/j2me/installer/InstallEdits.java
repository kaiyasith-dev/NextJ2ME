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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Changes the user made to a game's descriptor attributes before installing it. It is an
 * overlay: only what was changed, added or removed is stored, so attributes the user never saw
 * (for example ones that only exist in the JAR manifest) are left alone.
 */
final class InstallEdits {
	/** Attributes the installer needs as they are; never shown for editing. */
	static final Set<String> PROTECTED = Collections.unmodifiableSet(new HashSet<String>() {{
		add("Manifest-Version");
		add("MIDlet-Jar-URL");
		add("MIDlet-Jar-Size");
	}});

	private final Map<String, String> set = new HashMap<>();
	private final Set<String> removed = new HashSet<>();

	/** No changes. */
	static InstallEdits none() {
		return new InstallEdits();
	}

	/** Compares what the user was shown with what they typed. Protected keys are ignored. */
	static InstallEdits diff(Map<String, String> shown, Map<String, String> edited) {
		InstallEdits e = new InstallEdits();
		for (Map.Entry<String, String> entry : edited.entrySet()) {
			String key = entry.getKey();
			if (PROTECTED.contains(key)) {
				continue;
			}
			if (!entry.getValue().equals(shown.get(key))) {
				e.set.put(key, entry.getValue());
			}
		}
		for (String key : shown.keySet()) {
			if (!PROTECTED.contains(key) && !edited.containsKey(key)) {
				e.removed.add(key);
			}
		}
		return e;
	}

	/**
	 * The edits that result from applying these ones first and {@code later} on top of them. Used
	 * when the user goes back to the details and changes more: {@code later} was made against the
	 * already edited values, so the earlier edits must be kept.
	 */
	InstallEdits then(InstallEdits later) {
		InstallEdits r = new InstallEdits();
		r.set.putAll(set);
		r.removed.addAll(removed);
		for (String key : later.removed) {
			r.set.remove(key);
			r.removed.add(key);
		}
		for (Map.Entry<String, String> e : later.set.entrySet()) {
			r.removed.remove(e.getKey());
			r.set.put(e.getKey(), e.getValue());
		}
		return r;
	}

	boolean isEmpty() {
		return set.isEmpty() && removed.isEmpty();
	}

	/** The value after the edits: the edited one, null if removed, otherwise {@code original}. */
	String get(String key, String original) {
		if (removed.contains(key)) {
			return null;
		}
		String v = set.get(key);
		return v != null ? v : original;
	}

	/** Applies the edits to {@code attributes} in place. */
	void applyTo(Map<String, String> attributes) {
		for (String key : removed) {
			attributes.remove(key);
		}
		attributes.putAll(set);
	}

	/** One "Key: value" line per attribute, sorted by key; {@code hidden} keys are left out. */
	static String format(Map<String, String> attributes, Set<String> hidden) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : new TreeMap<>(attributes).entrySet()) {
			if (PROTECTED.contains(e.getKey()) || hidden.contains(e.getKey())) {
				continue;
			}
			sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
		}
		return sb.toString();
	}

	/**
	 * Parses "Key: value" lines. Blank lines are skipped.
	 *
	 * @throws IllegalArgumentException with the 1-based line number for a line that is not valid
	 */
	static Map<String, String> parse(String text) {
		Map<String, String> result = new HashMap<>();
		String[] lines = text.split("\\r?\\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.trim().isEmpty()) {
				continue;
			}
			int colon = line.indexOf(':');
			String key = colon > 0 ? line.substring(0, colon).trim() : "";
			if (key.isEmpty() || key.indexOf(' ') >= 0) {
				throw new IllegalArgumentException(String.valueOf(i + 1));
			}
			if (result.containsKey(key)) {
				throw new IllegalArgumentException(String.valueOf(i + 1));
			}
			result.put(key, line.substring(colon + 1).trim());
		}
		return result;
	}
}
