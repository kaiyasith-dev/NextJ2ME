/*
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

package ru.playsoftware.j2meloader.debugger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One scan from the first scan through all of its filter steps. Scope, type, byte order and
 * encoding are fixed when the session starts; each further scan only changes mode and value.
 * Sessions belong to one game run ({@link #generation}) and are never persisted.
 */
public final class ScanSession {
	/** One entry of the search history. */
	public static final class Step {
		public final ScanMode mode;
		public final String value;
		public final long results;
		public final long millis;
		public final boolean truncated;
		/** Free text for steps that are not scans (an import); null for scans. */
		public final String note;

		Step(ScanMode mode, String value, long results, long millis, boolean truncated, String note) {
			this.mode = mode;
			this.value = value;
			this.results = results;
			this.millis = millis;
			this.truncated = truncated;
			this.note = note;
		}

		@Override
		public String toString() {
			if (note != null) {
				return note + " → " + results;
			}
			String v = mode.needsValue() ? " " + value : "";
			return mode.label() + v + " → " + results + (truncated ? "+" : "") + " (" + millis + " ms)";
		}
	}

	public final int id;
	public final int generation;
	public final long createdAt = System.currentTimeMillis();
	public final ScanScope scope;
	public final ValueType type;
	public final boolean bigEndian;
	public final int alignment;
	public final StringEncoding encoding;
	private final List<Step> history = new ArrayList<>();
	private volatile MemorySnapshot snapshot;
	private volatile boolean truncated;
	/** Length in bytes of byte/string matches in raw memory. */
	private volatile int matchLength;

	ScanSession(int id, int generation, ScanParams p) {
		this.id = id;
		this.generation = generation;
		this.scope = p.scope;
		this.type = p.type;
		this.bigEndian = p.bigEndian;
		this.alignment = p.alignment;
		this.encoding = p.encoding;
	}

	public MemorySnapshot snapshot() {
		return snapshot;
	}

	void update(MemorySnapshot s, boolean wasTruncated, Step step, int newMatchLength) {
		this.snapshot = s;
		this.truncated = wasTruncated;
		if (newMatchLength > 0) {
			this.matchLength = newMatchLength;
		}
		synchronized (history) {
			history.add(step);
		}
	}

	Step step(ScanMode mode, String value, long results, long millis, boolean truncated) {
		return new Step(mode, value, results, millis, truncated, null);
	}

	Step note(String text, long results) {
		return new Step(ScanMode.UNKNOWN, "", results, 0, false, text);
	}

	public List<Step> history() {
		synchronized (history) {
			return Collections.unmodifiableList(new ArrayList<>(history));
		}
	}

	public long resultCount() {
		MemorySnapshot s = snapshot;
		return s == null ? 0 : s.size();
	}

	public boolean isTruncated() {
		return truncated;
	}

	public int matchLength() {
		return matchLength;
	}

	/** Whether a next scan is possible. */
	public boolean hasResults() {
		return resultCount() > 0;
	}

	public String title() {
		return "#" + id + " " + type.label() + " · " + scope.label() + " · " + resultCount()
				+ (truncated ? "+" : "") + " results";
	}
}
