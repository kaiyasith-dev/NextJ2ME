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

import java.util.Arrays;

/** An immutable typed value: numeric bits, a byte sequence or a string. */
public final class MemoryValue {
	public final ValueType type;
	/** Canonical bits for numeric types. */
	public final long bits;
	/** Bytes for {@link ValueType#BYTES}, encoded text for {@link ValueType#STRING}. */
	private final byte[] bytes;
	/** Text for {@link ValueType#STRING}; null means a null reference. */
	public final String text;

	private MemoryValue(ValueType type, long bits, byte[] bytes, String text) {
		this.type = type;
		this.bits = bits;
		this.bytes = bytes;
		this.text = text;
	}

	public static MemoryValue ofBits(ValueType type, long bits) {
		if (!type.isNumeric()) {
			throw new IllegalArgumentException("Not numeric: " + type);
		}
		return new MemoryValue(type, type.normalize(bits), null, null);
	}

	public static MemoryValue ofBytes(byte[] bytes) {
		return new MemoryValue(ValueType.BYTES, 0, bytes.clone(), null);
	}

	public static MemoryValue ofString(String text, StringEncoding enc) {
		return new MemoryValue(ValueType.STRING, 0, text == null ? null : enc.encode(text), text);
	}

	/** Parses user input for {@code type}; throws {@link IllegalArgumentException} with a readable message. */
	public static MemoryValue parse(ValueType type, String input, StringEncoding enc) {
		try {
			switch (type) {
				case BYTES:
					return ofBytes(parseHex(input));
				case STRING:
					if (input == null || input.isEmpty()) {
						throw new IllegalArgumentException("Empty text");
					}
					return ofString(input, enc);
				default:
					return ofBits(type, type.parse(input));
			}
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(e.getMessage() != null ? e.getMessage() : "Invalid number", e);
		}
	}

	/** Accepts {@code "3A 00 FF"}, {@code "3A00FF"}, {@code "0x3A,0x00"}. */
	public static byte[] parseHex(String input) {
		if (input == null) {
			throw new IllegalArgumentException("Empty hex string");
		}
		StringBuilder sb = new StringBuilder();
		String s = input.trim();
		for (String part : s.split("[\\s,;]+")) {
			String p = part;
			if (p.startsWith("0x") || p.startsWith("0X")) {
				p = p.substring(2);
			}
			sb.append(p);
		}
		String hex = sb.toString();
		if (hex.isEmpty()) {
			throw new IllegalArgumentException("Empty hex string");
		}
		if ((hex.length() & 1) != 0) {
			throw new IllegalArgumentException("Hex needs an even number of digits");
		}
		byte[] out = new byte[hex.length() / 2];
		for (int i = 0; i < out.length; i++) {
			int hi = Character.digit(hex.charAt(2 * i), 16);
			int lo = Character.digit(hex.charAt(2 * i + 1), 16);
			if (hi < 0 || lo < 0) {
				throw new IllegalArgumentException("Not a hex digit near '" + hex.substring(2 * i, 2 * i + 2) + "'");
			}
			out[i] = (byte) ((hi << 4) | lo);
		}
		return out;
	}

	public static String toHex(byte[] data, int off, int len) {
		StringBuilder sb = new StringBuilder(len * 3);
		for (int i = 0; i < len; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			int b = data[off + i] & 0xFF;
			sb.append(ValueType.HEX[b >> 4]).append(ValueType.HEX[b & 15]);
		}
		return sb.toString();
	}

	/** Copy of the byte payload (BYTES / STRING). */
	public byte[] bytes() {
		return bytes == null ? null : bytes.clone();
	}

	/** Byte payload without copying; callers must not modify it. */
	byte[] rawBytes() {
		return bytes;
	}

	public int byteLength() {
		return type.isNumeric() ? type.width() : bytes == null ? 0 : bytes.length;
	}

	/** Display form, e.g. {@code 100}, {@code 3A 00 FF}, {@code "hello"}. */
	public String format() {
		switch (type) {
			case BYTES:
				return toHex(bytes, 0, bytes.length);
			case STRING:
				return text == null ? "null" : text;
			default:
				return type.format(bits);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MemoryValue)) {
			return false;
		}
		MemoryValue v = (MemoryValue) o;
		if (type != v.type) {
			return false;
		}
		if (type.isNumeric()) {
			return bits == v.bits;
		}
		return Arrays.equals(bytes, v.bytes);
	}

	@Override
	public int hashCode() {
		return type.isNumeric() ? (int) (bits ^ (bits >>> 32)) + type.ordinal() : Arrays.hashCode(bytes);
	}

	@Override
	public String toString() {
		return type + ":" + format();
	}
}
