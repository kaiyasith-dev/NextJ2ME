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

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/**
 * Primitive and String fields of one object, or the static fields of one class.
 * Access goes through cached {@link Field} handles, so no per-read lookups happen.
 */
final class FieldRegion extends MemoryRegion {
	private final ClassInfo info;
	private final ClassInfo.FieldSlot[] slots;
	private final boolean isStatic;
	/** Null for static regions. */
	private final WeakReference<Object> target;
	private final WeakReference<Class<?>> staticOwner;
	private final int imageSize;
	private final ObjectRegistry registry;
	private long id = -1;

	/** Static fields of {@code info.cls}. */
	FieldRegion(ObjectRegistry registry, ClassInfo info) {
		this.registry = registry;
		this.info = info;
		this.slots = info.statics;
		this.isStatic = true;
		this.target = null;
		this.staticOwner = new WeakReference<Class<?>>(info.cls);
		this.imageSize = info.staticImageSize;
	}

	/** Instance fields of {@code object}. */
	FieldRegion(ObjectRegistry registry, ClassInfo info, Object object) {
		this.registry = registry;
		this.info = info;
		this.slots = info.instance;
		this.isStatic = false;
		this.target = new WeakReference<>(object);
		this.staticOwner = null;
		this.imageSize = info.instanceImageSize;
	}

	@Override
	public long id() {
		long v = id;
		if (v < 0) {
			Object o = isStatic ? staticOwner.get() : target.get();
			if (o == null) {
				throw new UnavailableException("Object was collected");
			}
			v = registry.idOf(o);
			id = v;
		}
		return v;
	}

	boolean isStaticRegion() {
		return isStatic;
	}

	@Override
	public Kind kind() {
		return isStatic ? Kind.STATIC : Kind.OBJECT;
	}

	@Override
	public boolean isAvailable() {
		return isStatic ? staticOwner.get() != null : target.get() != null;
	}

	@Override
	public String label() {
		return (isStatic ? "static " : "") + info.cls.getName();
	}

	@Override
	public String typeName() {
		return info.cls.getName();
	}

	@Override
	public boolean isByteAddressable() {
		return false;
	}

	@Override
	public int slotCount() {
		return slots.length;
	}

	@Override
	public ValueType slotType(int slot) {
		return slots[slot].type;
	}

	@Override
	public String slotName(int slot) {
		return slots[slot].name;
	}

	/** Declaring class of a slot (differs from {@link #typeName()} for inherited fields). */
	String slotOwner(int slot) {
		return slots[slot].owner;
	}

	@Override
	public int[] slotsAccepting(ValueType type) {
		return info.accepting(isStatic, type);
	}

	/** Index of a field by name, or -1. */
	int indexOf(String owner, String name) {
		return info.indexOf(isStatic, owner, name);
	}

	private Object instance() {
		if (isStatic) {
			return null;
		}
		Object o = target.get();
		if (o == null) {
			throw new UnavailableException("Object was collected");
		}
		return o;
	}

	private ClassInfo.FieldSlot slot(int slot) {
		if (slot < 0 || slot >= slots.length) {
			throw new UnavailableException("No such field slot: " + slot);
		}
		if (isStatic && staticOwner.get() == null) {
			throw new UnavailableException("Class is gone");
		}
		return slots[slot];
	}

	@Override
	public long readRaw(int slot, ValueType type, boolean bigEndian) {
		ClassInfo.FieldSlot s = slot(slot);
		if (s.type == ValueType.STRING) {
			throw new UnavailableException("Not a numeric field");
		}
		Object o = instance();
		try {
			return type.normalize(rawBits(s.field, s.type, o));
		} catch (IllegalAccessException | RuntimeException | LinkageError e) {
			throw new UnavailableException("Cannot read " + s.name, e);
		}
	}

	/** Raw (not normalized) bits of a field; floats as IEEE bits. */
	private static long rawBits(Field f, ValueType t, Object o) throws IllegalAccessException {
		switch (t) {
			case INT8:
			case UINT8:
				return f.getByte(o);
			case INT16:
			case UINT16:
				// char and short share this path; chars come back unsigned
				return f.getType() == char.class ? f.getChar(o) : f.getShort(o);
			case INT32:
				return f.getInt(o);
			case INT64:
				return f.getLong(o);
			case FLOAT:
				return Float.floatToRawIntBits(f.getFloat(o)) & 0xFFFFFFFFL;
			case DOUBLE:
				return Double.doubleToRawLongBits(f.getDouble(o));
			case BOOLEAN:
				return f.getBoolean(o) ? 1 : 0;
			default:
				throw new IllegalAccessException("Unsupported type " + t);
		}
	}

	@Override
	public void writeRaw(int slot, ValueType type, boolean bigEndian, long bits) {
		ClassInfo.FieldSlot s = slot(slot);
		if (s.type == ValueType.STRING) {
			throw new UnavailableException("Not a numeric field");
		}
		Object o = instance();
		try {
			setRaw(s.field, s.type, o, bits);
		} catch (IllegalAccessException | RuntimeException | LinkageError e) {
			throw new UnavailableException("Cannot write " + s.name, e);
		}
	}

	private static void setRaw(Field f, ValueType t, Object o, long bits) throws IllegalAccessException {
		switch (t) {
			case INT8:
			case UINT8:
				f.setByte(o, (byte) bits);
				break;
			case INT16:
			case UINT16:
				if (f.getType() == char.class) {
					f.setChar(o, (char) bits);
				} else {
					f.setShort(o, (short) bits);
				}
				break;
			case INT32:
				f.setInt(o, (int) bits);
				break;
			case INT64:
				f.setLong(o, bits);
				break;
			case FLOAT:
				f.setFloat(o, Float.intBitsToFloat((int) bits));
				break;
			case DOUBLE:
				f.setDouble(o, Double.longBitsToDouble(bits));
				break;
			case BOOLEAN:
				f.setBoolean(o, bits != 0);
				break;
			default:
				throw new IllegalAccessException("Unsupported type " + t);
		}
	}

	@Override
	public String readString(int slot) {
		ClassInfo.FieldSlot s = slot(slot);
		if (s.type != ValueType.STRING) {
			throw new UnavailableException("Not a String field");
		}
		Object o = instance();
		try {
			return (String) s.field.get(o);
		} catch (IllegalAccessException | RuntimeException | LinkageError e) {
			throw new UnavailableException("Cannot read " + s.name, e);
		}
	}

	@Override
	public void writeString(int slot, String value) {
		ClassInfo.FieldSlot s = slot(slot);
		if (s.type != ValueType.STRING) {
			throw new UnavailableException("Not a String field");
		}
		Object o = instance();
		try {
			s.field.set(o, value);
		} catch (IllegalAccessException | RuntimeException | LinkageError e) {
			throw new UnavailableException("Cannot write " + s.name, e);
		}
	}

	@Override
	public int imageSize() {
		return imageSize;
	}

	@Override
	public int imageOffset(int slot) {
		return slot >= 0 && slot < slots.length ? slots[slot].imageOffset : -1;
	}

	@Override
	public void readImage(int offset, byte[] dst, int dstOff, int len) {
		checkImage(offset, len);
		int end = offset + len;
		for (int i = 0; i < slots.length; i++) {
			ClassInfo.FieldSlot s = slots[i];
			if (s.imageOffset < 0) {
				continue;
			}
			int w = s.type.width();
			int from = s.imageOffset;
			int to = from + w;
			if (to <= offset || from >= end) {
				continue;
			}
			byte[] bytes = s.type.toBytes(readRaw(i, s.type, true), true);
			for (int b = Math.max(from, offset); b < Math.min(to, end); b++) {
				dst[dstOff + (b - offset)] = bytes[b - from];
			}
		}
	}

	@Override
	public void writeImage(int offset, byte[] src, int srcOff, int len) {
		checkImage(offset, len);
		int end = offset + len;
		for (int i = 0; i < slots.length; i++) {
			ClassInfo.FieldSlot s = slots[i];
			if (s.imageOffset < 0) {
				continue;
			}
			int w = s.type.width();
			int from = s.imageOffset;
			int to = from + w;
			if (to <= offset || from >= end) {
				continue;
			}
			byte[] bytes = s.type.toBytes(readRaw(i, s.type, true), true);
			for (int b = Math.max(from, offset); b < Math.min(to, end); b++) {
				bytes[b - from] = src[srcOff + (b - offset)];
			}
			writeRaw(i, s.type, true, s.type.fromBytes(bytes, 0, true));
		}
	}

	private void checkImage(int offset, int len) {
		if (offset < 0 || len < 0 || offset + len > imageSize) {
			throw new UnavailableException("Outside of region image");
		}
	}
}
