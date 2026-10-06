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

/** A value that is kept at a fixed value by the {@link FreezeEngine} while enabled. */
public final class MemoryFreeze extends MemoryTarget {
	private volatile MemoryValue value;
	private volatile boolean enabled;
	/** Consecutive failed attempts; drives the back-off of the freeze engine. */
	volatile int failures;

	MemoryFreeze(long uid, String name, MemoryReference ref, ValueType type, boolean bigEndian,
				 StringEncoding encoding, int length, MemoryValue value, boolean enabled) {
		super(uid, name, ref, type, bigEndian, encoding, length);
		this.value = value;
		this.enabled = enabled;
	}

	public MemoryValue value() {
		return value;
	}

	void setValue(MemoryValue value) {
		this.value = value;
		this.failures = 0;
	}

	public boolean isEnabled() {
		return enabled;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
		this.failures = 0;
	}
}
