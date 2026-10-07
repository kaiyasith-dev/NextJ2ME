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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class EditTest extends DebuggerTestBase {

	private MemoryValue val(ValueType t, String text) {
		return MemoryValue.parse(t, text, StringEncoding.UTF8);
	}

	@Test
	public void writeStaticIntAndReadBack() {
		ScanSession s = first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100");
		MemoryLocation loc = find(s, G + "health").location;
		MemoryValue back = dbg.write(loc, val(ValueType.INT32, "9999"));
		assertEquals(9999, back.bits);
		assertEquals(9999, TestGame.health);
		assertEquals(9999, dbg.read(loc).bits);
	}

	@Test
	public void writePrivateStaticAndPrimitiveTypes() {
		MemoryLocation hidden = find(first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "777"),
				G + "hidden").location;
		dbg.write(hidden, val(ValueType.INT32, "1"));
		assertEquals(1, TestGame.hidden());

		MemoryLocation speed = find(first(ScanScope.STATIC_FIELDS, ValueType.FLOAT, ScanMode.EXACT, "1.0"),
				G + "speed").location;
		dbg.write(speed, val(ValueType.FLOAT, "3.25"));
		assertEquals(3.25f, TestGame.speed, 0);

		MemoryLocation god = find(first(ScanScope.STATIC_FIELDS, ValueType.BOOLEAN, ScanMode.EXACT, "false"),
				G + "godMode").location;
		dbg.write(god, val(ValueType.BOOLEAN, "true"));
		assertTrue(TestGame.godMode);
	}

	@Test
	public void writeObjectFieldsOfEveryWidth() {
		ScanSession s = first(ScanScope.OBJECTS, ValueType.INT16, ScanMode.EXACT, "30");
		dbg.write(find(s, ".mp").location, val(ValueType.INT16, "-5"));
		assertEquals(-5, TestGame.player.mp);

		ScanSession c = first(ScanScope.OBJECTS, ValueType.UINT16, ScanMode.EXACT, "65");
		dbg.write(find(c, ".grade").location, val(ValueType.UINT16, "66"));
		assertEquals('B', TestGame.player.grade);

		ScanSession l = first(ScanScope.OBJECTS, ValueType.INT64, ScanMode.EXACT, "123456789012");
		dbg.write(find(l, ".xp").location, val(ValueType.INT64, "-1"));
		assertEquals(-1L, TestGame.player.xp);

		ScanSession d = first(ScanScope.OBJECTS, ValueType.DOUBLE, ScanMode.EXACT, "0.5");
		dbg.write(find(d, ".luck").location, val(ValueType.DOUBLE, "99.5"));
		assertEquals(99.5, TestGame.player.luck, 0);

		ScanSession b = first(ScanScope.OBJECTS, ValueType.INT8, ScanMode.EXACT, "7");
		dbg.write(find(b, ".level8").location, val(ValueType.INT8, "-3"));
		assertEquals(-3, TestGame.player.level8);
	}

	@Test
	public void writeArrayElementTypedAndRaw() {
		ScanSession s = first(ScanScope.ARRAYS, ValueType.INT32, ScanMode.EXACT, "42");
		MemoryLocation loc = dbg.results(s, 0, 1).get(0).location;
		dbg.write(loc, val(ValueType.INT32, "-7"));
		assertEquals(-7, TestGame.inventory[3]);

		// raw: overwrite one byte of an int[] image (big endian: byte 3 of element 0 is the low byte)
		ScanSession raw = first(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "3");
		MemoryLocation e0 = dbg.results(raw, 0, 1).get(0).location;
		dbg.write(e0.withType(ValueType.UINT8, 0).withSlot(e0.slot + 3), val(ValueType.UINT8, "200"));
		assertEquals(200, TestGame.inventory[0]);
		dbg.write(e0, val(ValueType.INT32, "0x01020304"));
		assertEquals(0x01020304, TestGame.inventory[0]);
	}

	@Test
	public void rawLittleEndianWrite() {
		ScanParams p = params(ScanScope.RAW, ValueType.INT32, ScanMode.EXACT, "0x01020304");
		TestGame.saveData = new byte[]{4, 3, 2, 1};
		p.bigEndian = false;
		ScanSession s = dbg.runNewScan(p, null, CancelToken.NEVER);
		MemoryLocation loc = dbg.results(s, 0, 1).get(0).location;
		dbg.write(loc, val(ValueType.INT32, "0x0A0B0C0D"));
		assertArrayEquals(new byte[]{0x0D, 0x0C, 0x0B, 0x0A}, TestGame.saveData);
	}

	@Test
	public void writeStringsInFieldsAndRaw() {
		MemoryLocation name = find(first(ScanScope.STATIC_FIELDS, ValueType.STRING, ScanMode.EQUAL_TO, "Hero"),
				G + "playerName").location;
		MemoryValue back = dbg.write(name, val(ValueType.STRING, "Legend"));
		assertEquals("Legend", back.text);
		assertEquals("Legend", TestGame.playerName);

		TestGame.saveData = "..NAME=bob..".getBytes();
		ScanSession s = first(ScanScope.RAW, ValueType.STRING, ScanMode.EXACT, "bob");
		MemoryLocation raw = dbg.results(s, 0, 1).get(0).location;
		dbg.write(raw, val(ValueType.STRING, "eve"));
		assertEquals("..NAME=eve..", new String(TestGame.saveData));
	}

	@Test
	public void rawBytesCanBeWrittenAndOpenedByAddress() {
		TestGame.saveData = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
		ScanSession s = first(ScanScope.RAW, ValueType.BYTES, ScanMode.EXACT, "05 06");
		MemoryLocation loc = dbg.results(s, 0, 1).get(0).location;
		MemoryDebugger.ViewTarget v = dbg.openView(loc);
		assertEquals(4, v.offset);
		assertTrue(v.raw);
		assertTrue(v.base >= 0x1000);
		MemoryValue back = dbg.write(loc, MemoryValue.ofBytes(new byte[]{(byte) 0xAA, (byte) 0xBB}));
		assertEquals("AA BB", back.format());
		assertEquals((byte) 0xAA, TestGame.saveData[4]);
		assertEquals((byte) 0xBB, TestGame.saveData[5]);
		// the Regions list opens an array by its virtual address
		MemoryDebugger.ViewTarget again = dbg.openView(AddressSpace.format(v.base + 4));
		assertEquals(4, again.offset);
		assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, 7}, dbg.readView(again, 4, 3));
	}

	@Test
	public void objectViewerShowsFieldImage() {
		MemoryLocation hp = find(first(ScanScope.OBJECTS, ValueType.INT32, ScanMode.EXACT, "4242"), ".score").location;
		MemoryDebugger.ViewTarget v = dbg.openView(hp);
		assertTrue(!v.raw);
		byte[] image = dbg.readView(v, 0, v.size());
		assertNotNull(image);
		int off = v.region.imageOffset(hp.slot);
		assertEquals(4242, ValueType.INT32.fromBytes(image, off, true));
		String text = MemoryDebugger.interpret(image, off, true);
		assertTrue(text, text.contains("Int:    4242"));
	}

	@Test
	public void interpretationMatchesTheSpecExample() {
		byte[] data = {0, 0, 0, 100, 0, 0, 0, 0};
		String t = MemoryDebugger.interpret(data, 0, true);
		assertTrue(t, t.contains("Byte:   0"));
		assertTrue(t, t.contains("Int:    100"));
		assertTrue(t, t.contains("Float:  1.4E-43"));
	}

	@Test
	public void typeMismatchIsRejectedWithoutTouchingTheGame() {
		MemoryLocation loc = find(first(ScanScope.STATIC_FIELDS, ValueType.INT32, ScanMode.EXACT, "100"),
				G + "health").location;
		try {
			dbg.write(loc, val(ValueType.FLOAT, "1.5"));
			fail();
		} catch (IllegalArgumentException expected) {
			assertEquals(100, TestGame.health);
		}
		// reading a String field as an int is simply unavailable
		MemoryLocation name = find(first(ScanScope.STATIC_FIELDS, ValueType.STRING, ScanMode.EQUAL_TO, "Hero"),
				G + "playerName").location;
		assertNull(dbg.read(name.withType(ValueType.INT32, 0)));
	}

	@Test
	public void writingToAGoneObjectFailsAsUnavailable() {
		MemoryLocation hp = null;
		for (MemoryDebugger.ScanResult r : dbg.results(first(ScanScope.OBJECTS, ValueType.INT32,
				ScanMode.EXACT, "4242"), 0, 10)) {
			hp = r.location;
		}
		assertNotNull(hp);
		MemoryLocation bogus = new MemoryLocation(hp.scope, 999_999, hp.slot, hp.type, true, StringEncoding.UTF8, 0);
		assertNull(dbg.read(bogus));
		assertEquals("Unavailable", dbg.describe(bogus));
		try {
			dbg.write(bogus, val(ValueType.INT32, "1"));
			fail();
		} catch (UnavailableException expected) {
			// reported, not crashed
		}
		MemoryLocation outOfRange = hp.withSlot(500);
		assertNull(dbg.read(outOfRange));
	}
}
