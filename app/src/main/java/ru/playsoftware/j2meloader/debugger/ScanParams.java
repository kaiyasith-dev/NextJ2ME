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

/** Everything the user chooses for one scan. */
public final class ScanParams {
	public static final int DEFAULT_MAX_CANDIDATES = 2_000_000;
	public static final int DEFAULT_GROUP_WINDOW = 8;
	public static final int MAX_GROUP_WINDOW = 1_000_000;
	/** Most values a group scan accepts; keeps its temporary lists small. */
	public static final int MAX_GROUP_VALUES = 16;

	public ScanScope scope = ScanScope.RAW;
	public ValueType type = ValueType.INT32;
	public ScanMode mode = ScanMode.EXACT;
	/** The value for modes that need one (exact, equal to, increased by, ...). */
	public String value = "";
	/** Byte order used when decoding multi-byte values from raw memory. */
	public boolean bigEndian = true;
	/** Step between decoded positions in raw memory; 0 means the natural width of the type. */
	public int alignment = 0;
	public StringEncoding encoding = StringEncoding.UTF8;
	/** Upper bound of remembered candidates; protects the game's heap. */
	public int maxCandidates = DEFAULT_MAX_CANDIDATES;
	/** Group scan: {@link #value} holds several values separated by ';' that must occur close together. */
	public boolean group;
	/** Group scan: how many positions (slots, or steps in raw memory) apart the values may be. */
	public int groupWindow = DEFAULT_GROUP_WINDOW;
	/** Group scan: the values must appear in the order given (not meaningful for object fields). */
	public boolean groupOrdered;
	/**
	 * Fuzzy scan: {@link #value} is a whole number that is searched as every integer size it fits
	 * (8, 16, 32 and 64 bits) at once; {@link #type} is ignored. Only for a new scan.
	 */
	public boolean fuzzy;

	public ScanParams copy() {
		ScanParams p = new ScanParams();
		p.scope = scope;
		p.type = type;
		p.mode = mode;
		p.value = value;
		p.bigEndian = bigEndian;
		p.alignment = alignment;
		p.encoding = encoding;
		p.maxCandidates = maxCandidates;
		p.group = group;
		p.groupWindow = groupWindow;
		p.groupOrdered = groupOrdered;
		p.fuzzy = fuzzy;
		return p;
	}

	/** Effective step in raw memory for a numeric type. */
	int rawStep() {
		if (alignment > 0) {
			return alignment;
		}
		return Math.max(1, type.width());
	}

	/**
	 * Checks that scope, type and mode make sense together and parses the value.
	 *
	 * @return the parsed target value, or null if the mode needs none
	 * @throws IllegalArgumentException with a message fit for the user
	 */
	public MemoryValue validate(boolean firstScan) {
		if (fuzzy) {
			return validateFuzzy(firstScan);
		}
		if (group) {
			return validateGroup(firstScan);
		}
		if (type == ValueType.BYTES && scope != ScanScope.RAW) {
			throw new IllegalArgumentException("Byte sequences can only be searched in Raw memory");
		}
		if (type == ValueType.STRING && (scope == ScanScope.ARRAYS)) {
			throw new IllegalArgumentException("Use Raw memory to search text inside arrays");
		}
		if (firstScan && !mode.canStartScan()) {
			throw new IllegalArgumentException("\"" + mode.label() + "\" needs a previous scan. "
					+ "Start with Exact value or Unknown initial value");
		}
		boolean opaque = !type.isNumeric();
		if (opaque) {
			boolean ok = mode == ScanMode.EXACT || mode == ScanMode.EQUAL_TO || mode == ScanMode.NOT_EQUAL_TO
					|| mode == ScanMode.CHANGED || mode == ScanMode.UNCHANGED
					|| (mode == ScanMode.UNKNOWN && scope != ScanScope.RAW);
			if (!ok) {
				throw new IllegalArgumentException("\"" + mode.label() + "\" is not available for "
						+ type.label());
			}
			if (firstScan && mode == ScanMode.NOT_EQUAL_TO && scope == ScanScope.RAW) {
				throw new IllegalArgumentException("Start a byte/text search with Exact value");
			}
		}
		if (alignment < 0) {
			throw new IllegalArgumentException("Alignment must not be negative");
		}
		if (mode.needsValue()) {
			if (value == null || value.trim().isEmpty()) {
				throw new IllegalArgumentException("Enter a value for \"" + mode.label() + "\"");
			}
			MemoryValue v = MemoryValue.parse(type, type == ValueType.STRING ? value : value.trim(), encoding);
			if ((mode == ScanMode.INCREASED_BY || mode == ScanMode.DECREASED_BY) && !type.isNumeric()) {
				throw new IllegalArgumentException("\"" + mode.label() + "\" needs a numeric type");
			}
			return v;
		}
		return null;
	}

	/** Integer sizes tried by a fuzzy scan, narrowest first; the signed type is preferred when the value fits. */
	private static final ValueType[][] FUZZY_TYPES = {
			{ValueType.INT8, ValueType.UINT8},
			{ValueType.INT16, ValueType.UINT16},
			{ValueType.INT32, ValueType.UINT32},
			{ValueType.INT64, ValueType.UINT64}};

	/**
	 * The integer types a fuzzy scan searches for {@link #value}: one per size the value fits.
	 *
	 * @throws IllegalArgumentException if the value is not a whole number that fits any size
	 */
	ValueType[] fuzzyTypes() {
		String v = value == null ? "" : value.trim();
		if (v.isEmpty()) {
			throw new IllegalArgumentException("Enter a whole number to search for");
		}
		java.util.List<ValueType> out = new java.util.ArrayList<>();
		for (ValueType[] sizes : FUZZY_TYPES) {
			for (ValueType t : sizes) {
				try {
					t.parse(v);
					out.add(t);
					break; // the signed type of this size, or else the unsigned one
				} catch (NumberFormatException ignored) {
					// does not fit this type
				}
			}
		}
		if (out.isEmpty()) {
			throw new IllegalArgumentException(
					"Enter a whole number that fits an integer of 8, 16, 32 or 64 bits");
		}
		return out.toArray(new ValueType[0]);
	}

	private MemoryValue validateFuzzy(boolean firstScan) {
		if (!firstScan) {
			throw new IllegalArgumentException("Any integer size is only for a new scan. "
					+ "Filter its results with a normal scan.");
		}
		if (group) {
			throw new IllegalArgumentException("Any integer size can not be combined with a group scan");
		}
		if (mode != ScanMode.EXACT && mode != ScanMode.EQUAL_TO) {
			throw new IllegalArgumentException("Any integer size uses \"Exact value\"");
		}
		if (alignment < 0) {
			throw new IllegalArgumentException("Alignment must not be negative");
		}
		ValueType first = fuzzyTypes()[0];
		return MemoryValue.ofBits(first, first.parse(value.trim()));
	}

	private MemoryValue validateGroup(boolean firstScan) {
		if (!firstScan) {
			throw new IllegalArgumentException("Group scan is only for a new scan. "
					+ "Filter its results with a normal scan.");
		}
		if (!type.isNumeric()) {
			throw new IllegalArgumentException("Group scan needs a numeric type");
		}
		if (mode != ScanMode.EXACT && mode != ScanMode.EQUAL_TO) {
			throw new IllegalArgumentException("Group scan uses \"Exact value\"");
		}
		if (groupWindow < 1 || groupWindow > MAX_GROUP_WINDOW) {
			throw new IllegalArgumentException("Window must be between 1 and " + MAX_GROUP_WINDOW);
		}
		if (alignment < 0) {
			throw new IllegalArgumentException("Alignment must not be negative");
		}
		return MemoryValue.ofBits(type, parseGroup()[0]);
	}

	/**
	 * Parses the ';' separated values of a group scan into canonical bits.
	 *
	 * @throws IllegalArgumentException if a value is invalid or fewer than two were given
	 */
	long[] parseGroup() {
		java.util.List<Long> out = new java.util.ArrayList<>();
		String[] parts = value == null ? new String[0] : value.split(";", -1);
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i].trim();
			if (part.isEmpty()) {
				if (i == parts.length - 1) {
					continue; // a trailing ';' is fine
				}
				throw new IllegalArgumentException("Empty value in the group (value " + (i + 1) + ")");
			}
			try {
				out.add(type.parse(part));
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Value " + (i + 1) + ": " + e.getMessage(), e);
			}
		}
		if (out.size() < 2) {
			throw new IllegalArgumentException("Enter at least two values separated by ;");
		}
		if (out.size() > MAX_GROUP_VALUES) {
			throw new IllegalArgumentException("A group can have at most " + MAX_GROUP_VALUES + " values");
		}
		long[] bits = new long[out.size()];
		for (int i = 0; i < bits.length; i++) {
			bits[i] = out.get(i);
		}
		return bits;
	}
}
