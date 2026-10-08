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
		/** The results as they were right after this step; null once dropped to save memory. */
		private MemorySnapshot snapshot;
		private int matchLength;
		/** Whether the session had hit its candidate limit at this step. */
		private boolean incomplete;

		Step(ScanMode mode, String value, long results, long millis, boolean truncated) {
			this.mode = mode;
			this.value = value;
			this.results = results;
			this.millis = millis;
			this.truncated = truncated;
		}

		/** Whether the results of this step are still available for {@link MemoryDebugger#restoreScanStep}. */
		public boolean isRestorable() {
			return snapshot != null;
		}

		@Override
		public String toString() {
			return describe(results);
		}

		/** Like {@link #toString()}, with another result count (the total of an any-size scan). */
		public String describe(long resultCount) {
			String v = mode.needsValue() ? " " + value : "";
			return mode.label() + v + " → " + resultCount + (truncated ? "+" : "") + " (" + millis + " ms)";
		}

		/** How many results this step had. */
		public long results() {
			return results;
		}
	}

	/** Candidates the history of one session may keep in total (the newest step is always kept). */
	static final long MAX_RETAINED_CANDIDATES = 3_000_000;

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
	/** Something the user should know about the results (for example skipped regions). */
	private volatile String warning;
	/** Steps whose results were dropped since the last {@link #takeDroppedSteps()}. */
	private int droppedSteps;
	/**
	 * Non-zero for the scans that came from one "any integer size" scan: they share this id and
	 * every further scan filters all of them together.
	 */
	private volatile int fuzzyGroup;

	ScanSession(int id, int generation, ScanParams p) {
		this.id = id;
		this.generation = generation;
		this.scope = p.scope;
		this.type = p.type;
		this.bigEndian = p.bigEndian;
		this.alignment = p.alignment;
		this.encoding = p.encoding;
	}

	void setFuzzyGroup(int group) {
		this.fuzzyGroup = group;
	}

	/** The id shared by the scans of one any-size scan, or 0 for an ordinary scan. */
	public int fuzzyGroup() {
		return fuzzyGroup;
	}

	/** Number of steps so far; the newest step has index {@code stepCount() - 1}. */
	int stepCount() {
		synchronized (history) {
			return history.size();
		}
	}

	public MemorySnapshot snapshot() {
		return snapshot;
	}

	void update(MemorySnapshot s, boolean wasTruncated, Step step, int newMatchLength) {
		synchronized (history) {
			this.snapshot = s;
			this.truncated = wasTruncated;
			if (newMatchLength > 0) {
				this.matchLength = newMatchLength;
			}
			step.snapshot = s;
			step.matchLength = this.matchLength;
			step.incomplete = wasTruncated;
			history.add(step);
			dropOldSnapshots();
		}
	}

	/** Keeps the results of recent steps only, so a long history cannot exhaust the game's heap. */
	private void dropOldSnapshots() {
		long total = retainedCount();
		for (int i = 0; i < history.size() - 1 && total > MAX_RETAINED_CANDIDATES; i++) {
			Step old = history.get(i);
			if (old.snapshot != null) {
				total -= old.snapshot.size();
				old.snapshot = null;
				droppedSteps++;
			}
		}
	}

	/** Drops the results of every step except the newest, to free memory. Returns how many were dropped. */
	int dropAllButNewestSnapshot() {
		synchronized (history) {
			int n = 0;
			for (int i = 0; i < history.size() - 1; i++) {
				Step st = history.get(i);
				if (st.snapshot != null) {
					st.snapshot = null;
					n++;
				}
			}
			return n;
		}
	}

	/** How many steps lost their results since the last call; resets the counter. */
	int takeDroppedSteps() {
		synchronized (history) {
			int n = droppedSteps;
			droppedSteps = 0;
			return n;
		}
	}

	void setWarning(String text) {
		this.warning = text;
	}

	/** A note about the results (e.g. regions that were skipped), or null. */
	public String warning() {
		return warning;
	}

	/** Candidates held by this session across all kept steps. */
	long retainedCount() {
		synchronized (history) {
			long total = 0;
			for (Step st : history) {
				if (st.snapshot != null) {
					total += st.snapshot.size();
				}
			}
			return total;
		}
	}

	/**
	 * Goes back to the results of an earlier step: they become the current results again and the
	 * later steps are discarded, so the next scan filters them as if it had just been done.
	 *
	 * @throws IllegalArgumentException if there is no such step
	 * @throws IllegalStateException    if the step's results were dropped to save memory
	 */
	void restoreTo(int index) {
		synchronized (history) {
			if (index < 0 || index >= history.size()) {
				throw new IllegalArgumentException("There is no step " + (index + 1));
			}
			Step st = history.get(index);
			if (st.snapshot == null) {
				throw new IllegalStateException("The results of step " + (index + 1)
						+ " were not kept to save memory");
			}
			while (history.size() > index + 1) {
				history.remove(history.size() - 1);
			}
			this.snapshot = st.snapshot;
			this.truncated = st.incomplete;
			this.matchLength = st.matchLength;
		}
	}

	Step step(ScanMode mode, String value, long results, long millis, boolean truncated) {
		return new Step(mode, value, results, millis, truncated);
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

	public String title() {
		return "#" + id + " " + type.label() + (fuzzyGroup != 0 ? " (any size)" : "") + " · " + scope.label() + " · " + resultCount()
				+ (truncated ? "+" : "") + " results";
	}
}
