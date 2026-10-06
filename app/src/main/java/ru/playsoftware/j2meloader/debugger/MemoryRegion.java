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
 * A contiguous piece of the game's inspectable state: the static fields of a class, the
 * fields of an object or a primitive array. Regions are cheap, short lived views; they never
 * keep the underlying object alive and report {@link UnavailableException} once it is gone.
 * <p>
 * Slots are either typed ({@link #isByteAddressable()} false: one slot per field or array
 * element) or byte offsets (true: any {@link ValueType} can be decoded at any offset).
 */
public abstract class MemoryRegion {
	public enum Kind {STATIC, OBJECT, ARRAY}

	/**
	 * Stable emulator-level identifier (never a host pointer). Assigned on first use, so regions
	 * that are merely looked at by a scan do not cost a registry entry.
	 *
	 * @throws UnavailableException if the object is already gone
	 */
	public abstract long id();

	public abstract Kind kind();

	public abstract boolean isAvailable();

	/** Human readable description, e.g. {@code static com.example.Game} or {@code int[64]}. */
	public abstract String label();

	/** Class name of the owner (class name, or array type like {@code int[]}). */
	public abstract String typeName();

	public abstract boolean isByteAddressable();

	/** Number of slots: fields/elements, or bytes when byte addressable. */
	public abstract int slotCount();

	/** Declared type of a typed slot; {@link ValueType#INT8} for byte offsets. */
	public abstract ValueType slotType(int slot);

	/** Field name or element index label of a slot. */
	public abstract String slotName(int slot);

	/** Slots a scan of {@code type} should visit; null means every slot (stepping is up to the caller). */
	public abstract int[] slotsAccepting(ValueType type);

	/** Reads a numeric slot and returns canonical bits for {@code type}. */
	public abstract long readRaw(int slot, ValueType type, boolean bigEndian);

	public abstract void writeRaw(int slot, ValueType type, boolean bigEndian, long bits);

	/** Reads a String slot (null for a null reference). */
	public abstract String readString(int slot);

	public abstract void writeString(int slot, String value);

	/** Size of the big-endian byte image of this region. */
	public abstract int imageSize();

	/** Offset of a slot inside the byte image, or -1 if it has none (String fields). */
	public abstract int imageOffset(int slot);

	/** Copies part of the big-endian byte image (String fields are not part of the image). */
	public abstract void readImage(int offset, byte[] dst, int dstOff, int len);

	public abstract void writeImage(int offset, byte[] src, int srcOff, int len);

	/**
	 * Bulk read of {@code count} slots starting at {@code start} with the given stride,
	 * as canonical bits for {@code type}. Subclasses override this for speed.
	 */
	public void readBulk(ValueType type, boolean bigEndian, int start, int count, int stride, long[] out) {
		for (int i = 0; i < count; i++) {
			out[i] = readRaw(start + i * stride, type, bigEndian);
		}
	}

	/** Reads {@code len} bytes at {@code offset} of the byte image into a new array. */
	public final byte[] readImage(int offset, int len) {
		byte[] out = new byte[len];
		readImage(offset, out, 0, len);
		return out;
	}
}
