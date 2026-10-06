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
 * A saved, named cheat of one game ("Infinite HP"). Enabled cheats are applied as soon as their
 * reference can be resolved, including right after the game started. With {@link #isFreeze()} the
 * value is enforced continuously, otherwise it is written once per activation.
 */
public final class MemoryCheat extends MemoryTarget {
	private volatile MemoryValue value;
	private volatile boolean freeze;
	private volatile boolean enabled;
	/** Whether the one-shot write was already done for the current activation. */
	volatile boolean applied;
	volatile int failures;

	MemoryCheat(long uid, String name, MemoryReference ref, ValueType type, boolean bigEndian,
				StringEncoding encoding, int length, MemoryValue value, boolean freeze, boolean enabled) {
		super(uid, name, ref, type, bigEndian, encoding, length);
		this.value = value;
		this.freeze = freeze;
		this.enabled = enabled;
	}

	public MemoryValue value() {
		return value;
	}

	void setValue(MemoryValue value) {
		this.value = value;
		this.applied = false;
		this.failures = 0;
	}

	public boolean isFreeze() {
		return freeze;
	}

	void setFreeze(boolean freeze) {
		this.freeze = freeze;
		this.applied = false;
	}

	public boolean isEnabled() {
		return enabled;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
		this.applied = false;
		this.failures = 0;
	}
}
