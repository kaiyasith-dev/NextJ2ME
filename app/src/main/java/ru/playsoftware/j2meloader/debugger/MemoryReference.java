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

import java.util.Arrays;

/**
 * A durable way to find a value again.
 * <ul>
 * <li>{@link Kind#STATIC_FIELD}: {@code com.example.Game.health}</li>
 * <li>{@link Kind#PATH}: a static field (or a named root such as {@code midlet}) followed by field
 * names and array indices, e.g. {@code com.example.Game.player.hp} or
 * {@code com.example.Game.inventory[3]}. It is re-resolved on every access, so it keeps working
 * when the game replaces the object and after the game was restarted.</li>
 * <li>{@link Kind#OBJECT} and {@link Kind#RAW}: valid for the current session only, they are
 * based on emulator object ids and virtual addresses. These are never persisted.</li>
 * </ul>
 */
public final class MemoryReference {
	public enum Kind {STATIC_FIELD, PATH, OBJECT, RAW}

	/** One navigation step: a field of an object or an element of an array / list. */
	public static final class Step {
		/** Declaring class of the field; null for index steps or when not recorded. */
		public final String owner;
		/** Field name; null for index steps. */
		public final String field;
		public final int index;

		private Step(String owner, String field, int index) {
			this.owner = owner;
			this.field = field;
			this.index = index;
		}

		public static Step field(String owner, String field) {
			return new Step(owner, field, -1);
		}

		public static Step index(int index) {
			return new Step(null, null, index);
		}

		public boolean isIndex() {
			return field == null;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Step)) {
				return false;
			}
			Step s = (Step) o;
			return index == s.index && eq(field, s.field) && eq(owner, s.owner);
		}

		@Override
		public int hashCode() {
			return (field == null ? 0 : field.hashCode()) * 31 + index;
		}

		@Override
		public String toString() {
			return isIndex() ? "[" + index + "]" : "." + field;
		}
	}

	public final Kind kind;
	/** Class of the static root field; null for a named root. */
	public final String rootClass;
	/** Static root field name, or the name of a named root ({@code midlet}, {@code displayable}). */
	public final String rootName;
	private final Step[] steps;
	/** OBJECT: object id; RAW: virtual address. */
	public final long id;
	/** OBJECT: slot index inside the object, class or array. */
	public final int slot;
	/** OBJECT: where the id lives (OBJECTS, ARRAYS or STATIC_FIELDS). */
	public final ScanScope scope;

	private MemoryReference(Kind kind, String rootClass, String rootName, Step[] steps,
							long id, int slot, ScanScope scope) {
		this.kind = kind;
		this.rootClass = rootClass;
		this.rootName = rootName;
		this.steps = steps;
		this.id = id;
		this.slot = slot;
		this.scope = scope;
	}

	public static MemoryReference staticField(String className, String field) {
		return new MemoryReference(Kind.STATIC_FIELD, className, field, new Step[0], 0, 0, ScanScope.STATIC_FIELDS);
	}

	/** Path rooted at a static reference field. */
	public static MemoryReference path(String rootClass, String rootField, Step[] steps) {
		return new MemoryReference(Kind.PATH, rootClass, rootField, steps.clone(), 0, 0, null);
	}

	/** Path rooted at a named root object such as {@code midlet}. */
	public static MemoryReference namedPath(String rootName, Step[] steps) {
		return new MemoryReference(Kind.PATH, null, rootName, steps.clone(), 0, 0, null);
	}

	public static MemoryReference object(ScanScope scope, long objectId, int slot) {
		return new MemoryReference(Kind.OBJECT, null, null, new Step[0], objectId, slot, scope);
	}

	public static MemoryReference raw(long address) {
		return new MemoryReference(Kind.RAW, null, null, new Step[0], address, 0, ScanScope.RAW);
	}

	public Step[] steps() {
		return steps.clone();
	}

	int stepCount() {
		return steps.length;
	}

	Step step(int i) {
		return steps[i];
	}

	/** Whether this reference can be saved and still mean something in a later session. */
	public boolean isPersistent() {
		return kind == Kind.STATIC_FIELD || kind == Kind.PATH;
	}

	/** Human readable form, e.g. {@code com.example.Game.player.hp}. */
	public String describe() {
		switch (kind) {
			case STATIC_FIELD:
				return rootClass + "." + rootName;
			case PATH: {
				StringBuilder sb = new StringBuilder();
				sb.append(rootClass != null ? rootClass + "." + rootName : rootName);
				for (Step s : steps) {
					sb.append(s);
				}
				return sb.toString();
			}
			case RAW:
				return AddressSpace.format(id);
			default:
				return String.format(java.util.Locale.ROOT, "object 0x%08X slot %d", id, slot);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MemoryReference)) {
			return false;
		}
		MemoryReference r = (MemoryReference) o;
		return kind == r.kind && id == r.id && slot == r.slot && eq(rootClass, r.rootClass)
				&& eq(rootName, r.rootName) && Arrays.equals(steps, r.steps);
	}

	@Override
	public int hashCode() {
		return kind.hashCode() * 31 + (int) id + slot + (rootName == null ? 0 : rootName.hashCode())
				+ Arrays.hashCode(steps);
	}

	@Override
	public String toString() {
		return describe();
	}

	private static boolean eq(Object a, Object b) {
		return a == null ? b == null : a.equals(b);
	}
}
