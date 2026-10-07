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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ValueTypeTest {
	private static void rejects(ValueType t, String text) {
		try {
			t.parse(text);
			fail("expected a NumberFormatException for " + t + " '" + text + "'");
		} catch (NumberFormatException expected) {
			// ok
		}
	}

	@Test
	public void integerRanges() {
		assertEquals(-128, ValueType.INT8.parse("-128"));
		assertEquals(127, ValueType.INT8.parse("127"));
		rejects(ValueType.INT8, "128");
		assertEquals(255, ValueType.UINT8.parse("255"));
		rejects(ValueType.UINT8, "-1");
		assertEquals(-32768, ValueType.INT16.parse("-32768"));
		rejects(ValueType.INT16, "32768");
		assertEquals(65535, ValueType.UINT16.parse("65535"));
		assertEquals(Integer.MIN_VALUE, ValueType.INT32.parse("-2147483648"));
		rejects(ValueType.INT32, "2147483648");
		rejects(ValueType.INT32, "abc");
		rejects(ValueType.INT32, "");
	}

	@Test
	public void hexInputIsTwosComplement() {
		assertEquals(-1, ValueType.INT8.parse("0xFF"));
		assertEquals(-1, ValueType.INT32.parse("0xFFFFFFFF"));
		assertEquals(-1, ValueType.INT64.parse("0xFFFFFFFFFFFFFFFF"));
		assertEquals(255, ValueType.UINT8.parse("0xFF"));
		assertEquals(0x1234, ValueType.INT32.parse("0x1234"));
		rejects(ValueType.UINT8, "0x1FF");
	}

	@Test
	public void int64FullRange() {
		assertEquals(Long.MIN_VALUE, ValueType.INT64.parse("-9223372036854775808"));
		assertEquals(Long.MAX_VALUE, ValueType.INT64.parse("9223372036854775807"));
		assertEquals(-1L, ValueType.INT64.parse("18446744073709551615"));
		rejects(ValueType.INT64, "18446744073709551616");
	}

	@Test
	public void floatsAndDoubles() {
		long f = ValueType.FLOAT.parse("1.4");
		assertEquals("1.4", ValueType.FLOAT.format(f));
		assertEquals(Float.floatToRawIntBits(1.4f) & 0xFFFFFFFFL, f);
		long d = ValueType.DOUBLE.parse("0.1");
		assertEquals(0.1, ValueType.DOUBLE.toDouble(d), 0);
		assertTrue(ValueType.FLOAT.valueEquals(ValueType.FLOAT.parse("0.0"), ValueType.FLOAT.parse("-0.0")));
		rejects(ValueType.FLOAT, "x1");
	}

	@Test
	public void booleans() {
		assertEquals(1, ValueType.BOOLEAN.parse("true"));
		assertEquals(1, ValueType.BOOLEAN.parse("ON"));
		assertEquals(0, ValueType.BOOLEAN.parse("0"));
		rejects(ValueType.BOOLEAN, "maybe");
		assertEquals("true", ValueType.BOOLEAN.format(1));
	}

	@Test
	public void byteCodecBothOrders() {
		long v = ValueType.INT32.parse("100");
		assertArrayEquals(new byte[]{0, 0, 0, 100}, ValueType.INT32.toBytes(v, true));
		assertArrayEquals(new byte[]{100, 0, 0, 0}, ValueType.INT32.toBytes(v, false));
		assertEquals(v, ValueType.INT32.fromBytes(new byte[]{0, 0, 0, 100}, 0, true));
		assertEquals(v, ValueType.INT32.fromBytes(new byte[]{100, 0, 0, 0}, 0, false));
		// the example of the spec: Int32 100 shown as float is a denormal
		assertEquals(Float.intBitsToFloat(100), Float.intBitsToFloat((int) v), 0);
		assertEquals("0x00000064", ValueType.INT32.formatHex(v));
		long s = ValueType.INT16.fromBytes(new byte[]{(byte) 0xFF, (byte) 0xFE}, 0, true);
		assertEquals(-2, s);
		assertEquals(0xFFFE, ValueType.UINT16.fromBytes(new byte[]{(byte) 0xFF, (byte) 0xFE}, 0, true));
	}

	@Test
	public void normalizeAndStorageCompatibility() {
		assertEquals(-1, ValueType.INT8.normalize(0xFF));
		assertEquals(255, ValueType.UINT8.normalize(-1));
		assertTrue(ValueType.UINT8.accepts(ValueType.INT8));
		assertTrue(ValueType.INT16.accepts(ValueType.UINT16));
		assertFalse(ValueType.INT32.accepts(ValueType.FLOAT));
		assertFalse(ValueType.INT8.accepts(ValueType.BOOLEAN));
		assertEquals(ValueType.UINT16, ValueType.fromClass(char.class));
		assertNull(ValueType.fromClass(Object.class));
		assertEquals(ValueType.STRING, ValueType.fromClass(String.class));
	}

	@Test
	public void deltaWrapsLikeTheStoredType() {
		// 127 + 1 wraps to -128 in a signed byte
		assertTrue(ValueType.INT8.isPlusDelta(-128, 127, 1, false));
		assertTrue(ValueType.INT32.isPlusDelta(70, 100, 30, true));
		assertFalse(ValueType.INT32.isPlusDelta(71, 100, 30, true));
		long a = ValueType.FLOAT.parse("1.5");
		long b = ValueType.FLOAT.parse("2.0");
		long d = ValueType.FLOAT.parse("0.5");
		assertTrue(ValueType.FLOAT.isPlusDelta(b, a, d, false));
		assertTrue(ValueType.FLOAT.isPlusDelta(a, b, d, true));
	}

	@Test
	public void memoryValueParsing() {
		assertArrayEquals(new byte[]{0x3A, 0, (byte) 0xFF}, MemoryValue.parseHex("3A 00 FF"));
		assertArrayEquals(new byte[]{0x3A, 0}, MemoryValue.parseHex("0x3A,0x00"));
		assertArrayEquals(new byte[]{0x12, 0x34}, MemoryValue.parseHex("1234"));
		try {
			MemoryValue.parseHex("123");
			fail();
		} catch (IllegalArgumentException expected) {
			// odd length
		}
		try {
			MemoryValue.parseHex("zz");
			fail();
		} catch (IllegalArgumentException expected) {
			// not hex
		}
		MemoryValue s = MemoryValue.parse(ValueType.STRING, "héllo", StringEncoding.UTF8);
		assertEquals(6, s.byteLength());
		assertEquals(5, MemoryValue.ofString("héllo", StringEncoding.ASCII).byteLength());
		assertEquals(10, MemoryValue.ofString("héllo", StringEncoding.UTF16BE).byteLength());
		assertEquals("3A 00", MemoryValue.ofBytes(new byte[]{0x3A, 0}).format());
		assertEquals(MemoryValue.ofBits(ValueType.INT32, 5), MemoryValue.parse(ValueType.INT32, " 5 ", StringEncoding.UTF8));
	}

	@Test
	public void addressParsing() {
		assertEquals(0x1A40, AddressSpace.parse("0x1A40"));
		assertEquals(0x1A40, AddressSpace.parse("1a40"));
		assertEquals(0x1A40, AddressSpace.parse("1A40h"));
		assertEquals("0x00001A40", AddressSpace.format(0x1A40));
	}
}
