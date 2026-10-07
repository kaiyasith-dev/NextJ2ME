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

import java.lang.ref.WeakReference;

/**
 * A primitive Java array. In typed mode every element is a slot; in byte-addressable ("raw")
 * mode every byte of the array's big-endian image is a slot and any {@link ValueType} can be
 * decoded at any offset.
 */
final class ArrayRegion extends MemoryRegion {
	private static final int[] NONE = new int[0];

	private final WeakReference<Object> array;
	private final ValueType elem;
	private final int elemWidth;
	private final int length;
	private final boolean raw;
	private final String typeName;
	private final ObjectRegistry registry;
	private long id = -1;

	ArrayRegion(ObjectRegistry registry, Object array, boolean raw) {
		this.registry = registry;
		Class<?> comp = array.getClass().getComponentType();
		ValueType t = comp == null ? null : ValueType.fromClass(comp);
		if (t == null || t == ValueType.STRING) {
			throw new IllegalArgumentException("Not a primitive array: " + array.getClass());
		}
		this.array = new WeakReference<>(array);
		this.elem = t;
		this.elemWidth = Math.max(1, t.width());
		this.length = java.lang.reflect.Array.getLength(array);
		this.raw = raw;
		this.typeName = comp.getName() + "[]";
	}

	@Override
	public long id() {
		long v = id;
		if (v < 0) {
			Object a = array.get();
			if (a == null) {
				throw new UnavailableException("Array was collected");
			}
			v = registry.idOf(a);
			id = v;
		}
		return v;
	}

	ValueType elementType() {
		return elem;
	}

	@Override
	public Kind kind() {
		return Kind.ARRAY;
	}

	@Override
	public boolean isAvailable() {
		return array.get() != null;
	}

	@Override
	public String label() {
		return typeName.substring(0, typeName.length() - 1) + length + "]";
	}

	@Override
	public String typeName() {
		return typeName;
	}

	@Override
	public boolean isByteAddressable() {
		return raw;
	}

	@Override
	public int slotCount() {
		return raw ? length * elemWidth : length;
	}

	@Override
	public ValueType slotType(int slot) {
		return raw ? ValueType.INT8 : elem;
	}

	@Override
	public String slotName(int slot) {
		return raw ? "+" + slot : "[" + slot + "]";
	}

	@Override
	public int[] slotsAccepting(ValueType type) {
		if (raw) {
			return null;
		}
		return type.accepts(elem) ? null : NONE;
	}

	private Object pin() {
		Object a = array.get();
		if (a == null) {
			throw new UnavailableException("Array was collected");
		}
		return a;
	}

	/** Raw (not normalized) bits of element {@code i}; floats as IEEE bits. */
	private long elementBits(Object a, int i) {
		switch (elem) {
			case INT8:
				return ((byte[]) a)[i];
			case INT16:
				return ((short[]) a)[i];
			case UINT16:
				return ((char[]) a)[i];
			case INT32:
				return ((int[]) a)[i];
			case INT64:
				return ((long[]) a)[i];
			case FLOAT:
				return Float.floatToRawIntBits(((float[]) a)[i]) & 0xFFFFFFFFL;
			case DOUBLE:
				return Double.doubleToRawLongBits(((double[]) a)[i]);
			case BOOLEAN:
				return ((boolean[]) a)[i] ? 1 : 0;
			default:
				throw new IllegalStateException();
		}
	}

	private void setElementBits(Object a, int i, long bits) {
		switch (elem) {
			case INT8:
				((byte[]) a)[i] = (byte) bits;
				break;
			case INT16:
				((short[]) a)[i] = (short) bits;
				break;
			case UINT16:
				((char[]) a)[i] = (char) bits;
				break;
			case INT32:
				((int[]) a)[i] = (int) bits;
				break;
			case INT64:
				((long[]) a)[i] = bits;
				break;
			case FLOAT:
				((float[]) a)[i] = Float.intBitsToFloat((int) bits);
				break;
			case DOUBLE:
				((double[]) a)[i] = Double.longBitsToDouble(bits);
				break;
			case BOOLEAN:
				((boolean[]) a)[i] = bits != 0;
				break;
			default:
				throw new IllegalStateException();
		}
	}

	/** Byte {@code off} of the big-endian image. */
	private int byteAt(Object a, int off) {
		int e = off / elemWidth;
		int b = off - e * elemWidth;
		return (int) (elementBits(a, e) >>> (8 * (elemWidth - 1 - b))) & 0xFF;
	}

	private void setByteAt(Object a, int off, int value) {
		int e = off / elemWidth;
		int b = off - e * elemWidth;
		int shift = 8 * (elemWidth - 1 - b);
		long bits = elementBits(a, e);
		bits = (bits & ~(0xFFL << shift)) | ((value & 0xFFL) << shift);
		setElementBits(a, e, bits);
	}

	@Override
	public long readRaw(int slot, ValueType type, boolean bigEndian) {
		Object a = pin();
		try {
			return read(a, slot, type, bigEndian);
		} catch (IndexOutOfBoundsException | ClassCastException e) {
			throw new UnavailableException("Outside of array", e);
		}
	}

	private long read(Object a, int slot, ValueType type, boolean bigEndian) {
		if (!raw) {
			if (slot < 0 || slot >= length) {
				throw new UnavailableException("Outside of array");
			}
			return type.normalize(elementBits(a, slot));
		}
		int w = type.width();
		if (slot < 0 || slot + w > length * elemWidth) {
			throw new UnavailableException("Outside of array");
		}
		if (w == elemWidth && type.accepts(elem) && bigEndian && slot % elemWidth == 0) {
			return type.normalize(elementBits(a, slot / elemWidth));
		}
		long v = 0;
		if (elem == ValueType.INT8) {
			byte[] b = (byte[]) a;
			for (int i = 0; i < w; i++) {
				long x = b[slot + i] & 0xFFL;
				v |= x << (bigEndian ? 8 * (w - 1 - i) : 8 * i);
			}
		} else {
			for (int i = 0; i < w; i++) {
				long x = byteAt(a, slot + i);
				v |= x << (bigEndian ? 8 * (w - 1 - i) : 8 * i);
			}
		}
		return type.normalize(v);
	}

	@Override
	public void readBulk(ValueType type, boolean bigEndian, int start, int count, int stride, long[] out) {
		Object a = pin();
		try {
			for (int i = 0; i < count; i++) {
				out[i] = read(a, start + i * stride, type, bigEndian);
			}
		} catch (IndexOutOfBoundsException | ClassCastException e) {
			throw new UnavailableException("Outside of array", e);
		}
	}

	@Override
	public void writeRaw(int slot, ValueType type, boolean bigEndian, long bits) {
		Object a = pin();
		try {
			if (!raw) {
				if (slot < 0 || slot >= length) {
					throw new UnavailableException("Outside of array");
				}
				setElementBits(a, slot, bits);
				return;
			}
			int w = type.width();
			if (slot < 0 || slot + w > length * elemWidth) {
				throw new UnavailableException("Outside of array");
			}
			byte[] bytes = type.toBytes(bits, bigEndian);
			for (int i = 0; i < w; i++) {
				setByteAt(a, slot + i, bytes[i]);
			}
		} catch (IndexOutOfBoundsException | ClassCastException e) {
			throw new UnavailableException("Outside of array", e);
		}
	}

	@Override
	public String readString(int slot) {
		throw new UnavailableException("Arrays hold no String slots");
	}

	@Override
	public void writeString(int slot, String value) {
		throw new UnavailableException("Arrays hold no String slots");
	}

	@Override
	public int imageSize() {
		return length * elemWidth;
	}

	@Override
	public int imageOffset(int slot) {
		return raw ? slot : slot * elemWidth;
	}

	@Override
	public void readImage(int offset, byte[] dst, int dstOff, int len) {
		Object a = pin();
		if (offset < 0 || len < 0 || offset + len > imageSize()) {
			throw new UnavailableException("Outside of array");
		}
		try {
			if (elem == ValueType.INT8) {
				System.arraycopy(a, offset, dst, dstOff, len);
				return;
			}
			for (int i = 0; i < len; i++) {
				dst[dstOff + i] = (byte) byteAt(a, offset + i);
			}
		} catch (IndexOutOfBoundsException | ClassCastException e) {
			throw new UnavailableException("Outside of array", e);
		}
	}

	@Override
	public void writeImage(int offset, byte[] src, int srcOff, int len) {
		Object a = pin();
		if (offset < 0 || len < 0 || offset + len > imageSize()) {
			throw new UnavailableException("Outside of array");
		}
		try {
			if (elem == ValueType.INT8) {
				System.arraycopy(src, srcOff, a, offset, len);
				return;
			}
			for (int i = 0; i < len; i++) {
				setByteAt(a, offset + i, src[srcOff + i]);
			}
		} catch (IndexOutOfBoundsException | ClassCastException e) {
			throw new UnavailableException("Outside of array", e);
		}
	}
}
