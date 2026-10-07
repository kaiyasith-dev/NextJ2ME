/*
 * Copyright 2026 ksdev
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

/** How candidates are selected by a scan. */
public enum ScanMode {
	EXACT("Exact value", true),
	UNKNOWN("Unknown initial value", false),
	INCREASED("Increased", false),
	DECREASED("Decreased", false),
	INCREASED_BY("Increased by", true),
	DECREASED_BY("Decreased by", true),
	CHANGED("Changed", false),
	UNCHANGED("Unchanged", false),
	EQUAL_TO("Equal to", true),
	NOT_EQUAL_TO("Not equal to", true);

	private final String label;
	private final boolean needsValue;

	ScanMode(String label, boolean needsValue) {
		this.label = label;
		this.needsValue = needsValue;
	}

	public String label() {
		return label;
	}

	public boolean needsValue() {
		return needsValue;
	}

	/** Modes that compare against the value remembered by the previous scan. */
	public boolean needsPrevious() {
		return this == INCREASED || this == DECREASED || this == INCREASED_BY
				|| this == DECREASED_BY || this == CHANGED || this == UNCHANGED;
	}

	/** Modes that may start a brand new scan. */
	public boolean canStartScan() {
		return !needsPrevious();
	}

	/**
	 * Evaluates this mode for numeric values.
	 *
	 * @param cur    current canonical bits
	 * @param prev   canonical bits remembered by the previous scan (ignored by value modes)
	 * @param target canonical bits of the user's value (ignored by modes without a value)
	 */
	public boolean matches(ValueType t, long cur, long prev, long target) {
		switch (this) {
			case UNKNOWN:
				return true;
			case EXACT:
			case EQUAL_TO:
				return t.valueEquals(cur, target);
			case NOT_EQUAL_TO:
				return !t.valueEquals(cur, target);
			case INCREASED:
				return t.isGreater(cur, prev);
			case DECREASED:
				return t.isLess(cur, prev);
			case INCREASED_BY:
				return t.isPlusDelta(cur, prev, target, false);
			case DECREASED_BY:
				return t.isPlusDelta(cur, prev, target, true);
			case CHANGED:
				return cur != prev;
			case UNCHANGED:
				return cur == prev;
			default:
				return false;
		}
	}
}
