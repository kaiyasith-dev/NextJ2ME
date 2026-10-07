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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** uint32 and uint64 (value level) and scanning, reading and writing them in a game. */
public class UnsignedTypesTest extends DebuggerTestBase {
	private static final String MAX64 = "18446744073709551615";

	private static void rejects(ValueType t, String text) {
		try {
			t.parse(text);
			fail("expected a NumberFormatException for " + t + " '" + text + "'");
		} catch (NumberFormatException expected) {
			// ok
		}
	}

	// ------------------------------------------------------------ the types themselves

	@Test
	public void labelsAreTheShortTypeNames() {
		assertEquals("int8", ValueType.INT8.label());
		assertEquals("uint8", ValueType.UINT8.label());
		assertEquals("int16", ValueType.INT16.label());
		assertEquals("uint16", ValueType.UINT16.label());
		assertEquals("int32", ValueType.INT32.label());
		assertEquals("uint32", ValueType.UINT32.label());
		assertEquals("int64", ValueType.INT64.label());
		assertEquals("uint64", ValueType.UINT64.label());
		assertEquals("Raw bytes", ValueType.BYTES.label());
	}

	@Test
	public void uint32ParsesAndFormatsTheFullUnsignedRange() {
		assertEquals(0L, ValueType.UINT32.parse("0"));
		assertEquals(4294967295L, ValueType.UINT32.parse("4294967295"));
		assertEquals(4294967295L, ValueType.UINT32.parse("0xFFFFFFFF"));
		rejects(ValueType.UINT32, "4294967296");
		rejects(ValueType.UINT32, "-1");
		assertEquals("4294967295", ValueType.UINT32.format(ValueType.UINT32.parse("4294967295")));
		assertEquals("0xFFFFFFFF", ValueType.UINT32.formatHex(4294967295L));
	}

	@Test
	public void uint64ParsesAndFormatsTheFullUnsignedRange() {
		assertEquals(-1L, ValueType.UINT64.parse(MAX64));
		assertEquals(Long.MIN_VALUE, ValueType.UINT64.parse("0x8000000000000000"));
		assertEquals(0L, ValueType.UINT64.parse("0"));
		assertEquals(Long.MAX_VALUE, ValueType.UINT64.parse("9223372036854775807"));
		rejects(ValueType.UINT64, "18446744073709551616");
		rejects(ValueType.UINT64, "-1");
		assertEquals(MAX64, ValueType.UINT64.format(-1L));
		assertEquals("9223372036854775808", ValueType.UINT64.format(Long.MIN_VALUE));
		assertEquals("123", ValueType.UINT64.format(123));
	}

	@Test
	public void uint64ComparesAsUnsigned() {
		long max = ValueType.UINT64.parse(MAX64);
		assertTrue("2^64-1 is bigger than 5", ValueType.UINT64.isGreater(max, 5));
		assertTrue(ValueType.UINT64.isLess(5, max));
		assertFalse("signed comparison would say the opposite", ValueType.INT64.isGreater(max, 5));
		assertEquals(1.8446744073709552E19, ValueType.UINT64.toDouble(max), 1e5);
	}

	@Test
	public void deltasWrapAtTheEndOfTheUnsignedRange() {
		assertTrue(ValueType.UINT32.isPlusDelta(0, 4294967295L, 1, false));
		assertTrue(ValueType.UINT32.isPlusDelta(4294967295L, 0, 1, true));
		assertTrue(ValueType.UINT64.isPlusDelta(0, -1L, 1, false));
	}

	@Test
	public void storageIsSharedWithTheSignedTypeOfTheSameWidth() {
		assertTrue(ValueType.UINT32.accepts(ValueType.INT32));
		assertTrue(ValueType.INT32.accepts(ValueType.UINT32));
		assertTrue(ValueType.UINT64.accepts(ValueType.INT64));
		assertTrue(ValueType.INT64.accepts(ValueType.UINT64));
		assertFalse(ValueType.UINT32.accepts(ValueType.INT64));
		assertFalse(ValueType.UINT64.accepts(ValueType.INT32));
		assertTrue(ValueType.UINT32.isUnsigned() && ValueType.UINT64.isUnsigned());
	}

	@Test
	public void bytesDecodeAsUnsigned() {
		byte[] ff4 = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
		assertEquals(4294967295L, ValueType.UINT32.fromBytes(ff4, 0, true));
		assertEquals(-1L, ValueType.INT32.fromBytes(ff4, 0, true));
		byte[] ff8 = {-1, -1, -1, -1, -1, -1, -1, -1};
		assertEquals(MAX64, ValueType.UINT64.format(ValueType.UINT64.fromBytes(ff8, 0, false)));
		String table = MemoryDebugger.interpret(new byte[]{-1, -1, -1, -1, 0, 0, 0, 0}, 0, true);
		assertTrue(table, table.contains("(u4294967295)"));
	}

	// ------------------------------------------------------------ in a running game

	@Test
	public void uint32FindsAnIntFieldHoldingAHugeValue() {
		TestGame.money = -1; // 0xFFFFFFFF
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.UINT32, ScanMode.EXACT, "4294967295");
		assertHas(s, G + "money");
		assertEquals("4294967295", dbg.results(s, 0, 1).get(0).previous.format());
		assertHas(first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "-1"), G + "money");
		// an ordinary value is found by the unsigned type too
		assertHas(first(ScanScope.STATIC_FIELDS, ValueType.UINT32, ScanMode.EXACT, "100"), G + "health");
	}

	@Test
	public void uint64FindsALongFieldHoldingTheMaximum() {
		TestGame.player.xp = -1L;
		ScanSession s = first(ScanScope.OBJECTS, ValueType.UINT64, ScanMode.EXACT, MAX64);
		assertHas(s, ".xp");
		assertEquals(MAX64, dbg.results(s, 0, 1).get(0).previous.format());
	}

	@Test
	public void uint64IncreasedIsUnsigned() {
		TestGame.player.xp = 5;
		ScanSession s = first(ScanScope.OBJECTS, ValueType.UINT64, ScanMode.UNKNOWN, "");
		TestGame.player.xp = -1L; // 18446744073709551615: a big increase when unsigned
		next(ScanScope.OBJECTS, ValueType.UINT64, ScanMode.INCREASED, "");
		assertEquals(names(s).toString(), 1, s.resultCount());
		assertHas(s, ".xp");
		next(ScanScope.OBJECTS, ValueType.UINT64, ScanMode.DECREASED, "");
		assertEquals(0, s.resultCount());
	}

	@Test
	public void uint32WorksOnArrayElementsAndRawMemory() {
		TestGame.inventory[0] = -1;
		assertEquals(1, first(ScanScope.ARRAYS, ValueType.UINT32, ScanMode.EXACT, "4294967295").resultCount());
		assertEquals(1, first(ScanScope.RAW, ValueType.UINT32, ScanMode.EXACT, "0xFFFFFFFF").resultCount());
		ScanParams little = params(ScanScope.RAW, ValueType.UINT32, ScanMode.EXACT, "4294967295");
		little.bigEndian = false;
		assertEquals("all ones read the same in either byte order", 1,
				dbg.runNewScan(little, null, CancelToken.NEVER).resultCount());
	}

	@Test
	public void writingUnsignedValuesStoresTheRightBits() {
		MemoryLocation money = find(first(ScanScope.STATIC_FIELDS, ValueType.UINT32, ScanMode.UNKNOWN, ""),
				G + "money").location;
		MemoryValue back = dbg.write(money, MemoryValue.parse(ValueType.UINT32, "4294967295", StringEncoding.UTF8));
		assertEquals(-1, TestGame.money);
		assertEquals("4294967295", back.format());

		MemoryLocation xp = find(first(ScanScope.OBJECTS, ValueType.UINT64, ScanMode.UNKNOWN, ""), ".xp").location;
		MemoryValue back64 = dbg.write(xp, MemoryValue.parse(ValueType.UINT64, MAX64, StringEncoding.UTF8));
		assertEquals(-1L, TestGame.player.xp);
		assertEquals(MAX64, back64.format());
		// the same bits read back as the signed type
		assertEquals("-1", dbg.read(xp.withType(ValueType.INT64, 0)).format());
	}

	@Test
	public void groupScanAndInvalidInputWorkWithUnsignedTypes() {
		TestGame.money = -1;
		TestGame.health = 7;
		ScanParams p = params(ScanScope.STATIC_FIELDS, ValueType.UINT32, ScanMode.EXACT, "4294967295;7");
		p.group = true;
		assertEquals(2, dbg.runNewScan(p, null, CancelToken.NEVER).resultCount());
		try {
			first(ScanScope.STATIC_FIELDS, ValueType.UINT32, ScanMode.EXACT, "-5");
			fail();
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("uint32"));
		}
	}
}
