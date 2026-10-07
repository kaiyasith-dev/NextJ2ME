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

/**
 * A concrete place inside the current session: a slot of a region together with the way to
 * interpret it. This is what scan results point to; it is not persisted
 * (see {@link MemoryReference} for that).
 */
public final class MemoryLocation {
	public final ScanScope scope;
	/** Stable emulator object / class id of the region. */
	public final long regionId;
	/** Slot index (typed regions) or byte offset (raw regions). */
	public final int slot;
	public final ValueType type;
	public final boolean bigEndian;
	public final StringEncoding encoding;
	/** Byte length for {@link ValueType#BYTES} / {@link ValueType#STRING} locations. */
	public final int length;

	public MemoryLocation(ScanScope scope, long regionId, int slot, ValueType type,
						  boolean bigEndian, StringEncoding encoding, int length) {
		this.scope = scope;
		this.regionId = regionId;
		this.slot = slot;
		this.type = type;
		this.bigEndian = bigEndian;
		this.encoding = encoding;
		this.length = length;
	}

	public MemoryLocation withType(ValueType newType, int newLength) {
		return new MemoryLocation(scope, regionId, slot, newType, bigEndian, encoding, newLength);
	}

	public MemoryLocation withSlot(int newSlot) {
		return new MemoryLocation(scope, regionId, newSlot, type, bigEndian, encoding, length);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MemoryLocation)) {
			return false;
		}
		MemoryLocation l = (MemoryLocation) o;
		return scope == l.scope && regionId == l.regionId && slot == l.slot && type == l.type
				&& bigEndian == l.bigEndian && length == l.length;
	}

	@Override
	public int hashCode() {
		return (int) (regionId * 31 + slot) * 31 + scope.ordinal() * 7 + type.ordinal();
	}

	@Override
	public String toString() {
		return scope + ":" + regionId + "+" + slot + " " + type;
	}
}
