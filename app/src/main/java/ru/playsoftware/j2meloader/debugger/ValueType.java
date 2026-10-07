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

/**
 * Value types understood by the memory debugger.
 * <p>
 * Numeric values are carried around as a normalized {@code long} bit pattern ("bits"):
 * integers are sign- or zero-extended according to their signedness, floats keep their
 * raw IEEE bits in the low 32 bits and doubles keep all 64 bits. Two equal values therefore
 * always have equal bits, which makes snapshot comparison a plain {@code long} compare.
 */
public enum ValueType {
	INT8("int8", 1),
	UINT8("uint8", 1),
	INT16("int16", 2),
	UINT16("uint16", 2),
	INT32("int32", 4),
	UINT32("uint32", 4),
	INT64("int64", 8),
	UINT64("uint64", 8),
	FLOAT("Float", 4),
	DOUBLE("Double", 8),
	BOOLEAN("Boolean", 1),
	BYTES("Raw bytes", 0),
	STRING("String", 0);

	private final String label;
	private final int width;

	ValueType(String label, int width) {
		this.label = label;
		this.width = width;
	}

	public String label() {
		return label;
	}

	/** Width in bytes of a numeric value, 0 for {@link #BYTES} and {@link #STRING}. */
	public int width() {
		return width;
	}

	public boolean isNumeric() {
		return this != BYTES && this != STRING;
	}

	public boolean isFloating() {
		return this == FLOAT || this == DOUBLE;
	}

	public boolean isUnsigned() {
		return this == UINT8 || this == UINT16 || this == UINT32 || this == UINT64;
	}

	/**
	 * Whether a slot of type {@code slot} (as declared by a field or array) can be scanned
	 * when this type was selected. Signed and unsigned flavours share their storage.
	 */
	public boolean accepts(ValueType slot) {
		if (slot == this) {
			return true;
		}
		switch (this) {
			case INT8:
			case UINT8:
				return slot == INT8 || slot == UINT8;
			case INT16:
			case UINT16:
				return slot == INT16 || slot == UINT16;
			case INT32:
			case UINT32:
				return slot == INT32 || slot == UINT32;
			case INT64:
			case UINT64:
				return slot == INT64 || slot == UINT64;
			default:
				return false;
		}
	}

	/** Normalizes raw bits (any sign/zero extension) into this type's canonical bits. */
	public long normalize(long raw) {
		switch (this) {
			case INT8:
				return (byte) raw;
			case UINT8:
				return raw & 0xFFL;
			case INT16:
				return (short) raw;
			case UINT16:
				return raw & 0xFFFFL;
			case INT32:
				return (int) raw;
			case UINT32:
				return raw & 0xFFFFFFFFL;
			case FLOAT:
				return raw & 0xFFFFFFFFL;
			case BOOLEAN:
				return (raw & 0xFFL) != 0 ? 1 : 0;
			default:
				return raw;
		}
	}

	/** Numeric value of canonical bits as a double (all numeric types). */
	public double toDouble(long bits) {
		switch (this) {
			case FLOAT:
				return Float.intBitsToFloat((int) bits);
			case DOUBLE:
				return Double.longBitsToDouble(bits);
			case UINT64:
				return (double) (bits >>> 1) * 2.0 + (bits & 1);
			default:
				return (double) bits;
		}
	}

	public boolean valueEquals(long a, long b) {
		if (a == b) {
			return true;
		}
		if (isFloating()) {
			// 0.0 == -0.0 for the user, NaN never equals a different NaN payload
			return toDouble(a) == toDouble(b);
		}
		return false;
	}

	public boolean isGreater(long a, long b) {
		if (isFloating()) {
			return toDouble(a) > toDouble(b);
		}
		if (this == UINT64) {
			return (a ^ Long.MIN_VALUE) > (b ^ Long.MIN_VALUE);
		}
		return a > b;
	}

	public boolean isLess(long a, long b) {
		if (isFloating()) {
			return toDouble(a) < toDouble(b);
		}
		if (this == UINT64) {
			return (a ^ Long.MIN_VALUE) < (b ^ Long.MIN_VALUE);
		}
		return a < b;
	}

	/**
	 * Whether {@code cur == prev + delta}, or {@code prev - delta} when {@code subtract} is set.
	 * Integers wrap the way the stored type does; floats compare with a small tolerance.
	 */
	public boolean isPlusDelta(long cur, long prev, long delta, boolean subtract) {
		if (isFloating()) {
			double d = toDouble(delta);
			double expected = subtract ? toDouble(prev) - d : toDouble(prev) + d;
			double actual = toDouble(cur);
			double eps = Math.max(1e-6, Math.abs(d) * 1e-4);
			return Math.abs(actual - expected) <= eps;
		}
		return cur == normalize(subtract ? prev - delta : prev + delta);
	}

	/** Parses user input into canonical bits. */
	public long parse(String text) {
		if (text == null) {
			throw new NumberFormatException("Empty value");
		}
		String s = text.trim();
		if (s.isEmpty()) {
			throw new NumberFormatException("Empty value");
		}
		switch (this) {
			case FLOAT:
				return Float.floatToRawIntBits(Float.parseFloat(s)) & 0xFFFFFFFFL;
			case DOUBLE:
				return Double.doubleToRawLongBits(Double.parseDouble(s));
			case BOOLEAN: {
				String l = s.toLowerCase(java.util.Locale.ROOT);
				if (l.equals("true") || l.equals("1") || l.equals("on") || l.equals("yes")) {
					return 1;
				}
				if (l.equals("false") || l.equals("0") || l.equals("off") || l.equals("no")) {
					return 0;
				}
				throw new NumberFormatException("Not a boolean: " + s);
			}
			case BYTES:
			case STRING:
				throw new NumberFormatException("Not a numeric type: " + this);
			default:
				return parseIntegral(s);
		}
	}

	private long parseIntegral(String s) {
		boolean negative = false;
		String body = s;
		if (body.startsWith("-")) {
			negative = true;
			body = body.substring(1);
		} else if (body.startsWith("+")) {
			body = body.substring(1);
		}
		long v;
		if (body.startsWith("0x") || body.startsWith("0X")) {
			String hex = body.substring(2);
			if (hex.isEmpty() || hex.length() > 16) {
				throw new NumberFormatException("Bad hex value: " + s);
			}
			v = parseUnsignedHex(hex);
		} else {
			if (this == UINT64) {
				v = parseUnsignedDecimal(body); // the full range up to 18446744073709551615
				if (negative && v != 0) {
					throw new NumberFormatException("Out of range for " + label + ": " + s);
				}
			} else if (this == INT64) {
				// accepts Long.MIN_VALUE and, like a hex editor, the unsigned range of 64 bit input
				v = parseUnsignedDecimal(body);
				if (negative && v < 0 && v != Long.MIN_VALUE) {
					throw new NumberFormatException("Out of range for " + label + ": " + s);
				}
			} else {
				v = Long.parseLong(body);
			}
		}
		if (negative) {
			v = -v;
		}
		long min;
		long max;
		switch (this) {
			case INT8:
				min = Byte.MIN_VALUE;
				max = Byte.MAX_VALUE;
				break;
			case UINT8:
				min = 0;
				max = 0xFF;
				break;
			case INT16:
				min = Short.MIN_VALUE;
				max = Short.MAX_VALUE;
				break;
			case UINT16:
				min = 0;
				max = 0xFFFF;
				break;
			case INT32:
				min = Integer.MIN_VALUE;
				max = Integer.MAX_VALUE;
				break;
			case UINT32:
				min = 0;
				max = 0xFFFFFFFFL;
				break;
			default:
				return v;
		}
		boolean hexInput = body.startsWith("0x") || body.startsWith("0X");
		if (hexInput && !negative && this != UINT8 && this != UINT16) {
			// 0xFFFFFFFF for an Int32 means -1 in a memory editor
			long mask = width == 4 ? 0xFFFFFFFFL : width == 2 ? 0xFFFFL : 0xFFL;
			if (v >= 0 && v <= mask) {
				return normalize(v);
			}
		}
		if (v < min || v > max) {
			throw new NumberFormatException("Out of range for " + label + ": " + s);
		}
		return normalize(v);
	}

	private static long parseUnsignedHex(String hex) {
		long v = 0;
		for (int i = 0; i < hex.length(); i++) {
			int d = Character.digit(hex.charAt(i), 16);
			if (d < 0) {
				throw new NumberFormatException("Bad hex value: " + hex);
			}
			v = (v << 4) | d;
		}
		return v;
	}

	private static long parseUnsignedDecimal(String body) {
		if (body.length() < 19) {
			return Long.parseLong(body);
		}
		java.math.BigInteger bi = new java.math.BigInteger(body);
		if (bi.bitLength() > 64) {
			throw new NumberFormatException("Out of range for Long: " + body);
		}
		return bi.longValue();
	}

	/** Formats canonical bits for display. */
	public String format(long bits) {
		switch (this) {
			case FLOAT:
				return Float.toString(Float.intBitsToFloat((int) bits));
			case DOUBLE:
				return Double.toString(Double.longBitsToDouble(bits));
			case BOOLEAN:
				return bits != 0 ? "true" : "false";
			case BYTES:
			case STRING:
				return "?";
			case UINT64:
				return bits >= 0 ? Long.toString(bits)
						: java.math.BigInteger.valueOf(bits >>> 1).shiftLeft(1)
						.add(java.math.BigInteger.valueOf(bits & 1)).toString();
			default:
				return Long.toString(bits);
		}
	}

	/** Hex representation of canonical bits, e.g. {@code 0x00000064} for Int32 100. */
	public String formatHex(long bits) {
		int w = Math.max(1, width);
		StringBuilder sb = new StringBuilder("0x");
		for (int i = w - 1; i >= 0; i--) {
			int b = (int) (bits >>> (8 * i)) & 0xFF;
			sb.append(HEX[b >> 4]).append(HEX[b & 15]);
		}
		return sb.toString();
	}

	/** Encodes canonical bits into {@link #width()} bytes. */
	public byte[] toBytes(long bits, boolean bigEndian) {
		byte[] out = new byte[width];
		for (int i = 0; i < width; i++) {
			int shift = bigEndian ? 8 * (width - 1 - i) : 8 * i;
			out[i] = (byte) (bits >>> shift);
		}
		return out;
	}

	/** Decodes {@link #width()} bytes at {@code off} into canonical bits. */
	public long fromBytes(byte[] src, int off, boolean bigEndian) {
		long v = 0;
		for (int i = 0; i < width; i++) {
			long b = src[off + i] & 0xFFL;
			int shift = bigEndian ? 8 * (width - 1 - i) : 8 * i;
			v |= b << shift;
		}
		return normalize(v);
	}

	static final char[] HEX = "0123456789ABCDEF".toCharArray();

	/** Maps a Java field/array element class to the matching slot type, or null. */
	public static ValueType fromClass(Class<?> c) {
		if (c == int.class) {
			return INT32;
		} else if (c == byte.class) {
			return INT8;
		} else if (c == short.class) {
			return INT16;
		} else if (c == char.class) {
			return UINT16;
		} else if (c == long.class) {
			return INT64;
		} else if (c == float.class) {
			return FLOAT;
		} else if (c == double.class) {
			return DOUBLE;
		} else if (c == boolean.class) {
			return BOOLEAN;
		} else if (c == String.class) {
			return STRING;
		}
		return null;
	}
}
