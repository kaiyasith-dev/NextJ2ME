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
 * Something the debugger keeps an eye on or enforces: a {@link MemoryReference} plus the way to
 * interpret it. Base class of {@link MemoryWatch}, {@link MemoryFreeze} and {@link MemoryCheat}.
 */
public abstract class MemoryTarget {
	/** Result of the last attempt to reach the target. */
	public enum Status {PENDING, OK, UNAVAILABLE}

	private final long uid;
	private volatile String name;
	private volatile MemoryReference ref;
	private volatile ValueType type;
	private volatile boolean bigEndian;
	private volatile StringEncoding encoding;
	/** Byte length for BYTES / STRING in raw memory. */
	private volatile int length;
	private volatile Status status = Status.PENDING;

	protected MemoryTarget(long uid, String name, MemoryReference ref, ValueType type,
						   boolean bigEndian, StringEncoding encoding, int length) {
		this.uid = uid;
		this.name = name;
		this.ref = ref;
		this.type = type;
		this.bigEndian = bigEndian;
		this.encoding = encoding;
		this.length = length;
	}

	public long uid() {
		return uid;
	}

	public String name() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public MemoryReference ref() {
		return ref;
	}

	public ValueType type() {
		return type;
	}

	public boolean bigEndian() {
		return bigEndian;
	}

	public StringEncoding encoding() {
		return encoding;
	}

	public int length() {
		return length;
	}

	public Status status() {
		return status;
	}

	void setStatus(Status status) {
		this.status = status;
	}

	/** Changes the way the value is interpreted; used by "change type". */
	void retype(ValueType newType, int newLength) {
		this.type = newType;
		this.length = newLength;
		this.status = Status.PENDING;
	}

	/** Re-points the target (used when a session-only reference was upgraded to a durable one). */
	void setRef(MemoryReference newRef) {
		this.ref = newRef;
		this.status = Status.PENDING;
	}

	public boolean isPersistent() {
		return ref.isPersistent();
	}
}
