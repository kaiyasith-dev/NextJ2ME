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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/**
 * Cached reflection metadata of one game class. Built once per class so scans never
 * re-enumerate fields.
 */
final class ClassInfo {
	/** One scannable field. */
	static final class FieldSlot {
		final Field field;
		final ValueType type;
		final String name;
		final String owner;
		final boolean isStatic;
		/** Offset inside the big-endian image of the region, -1 for strings. */
		int imageOffset = -1;

		FieldSlot(Field field, ValueType type) {
			this.field = field;
			this.type = type;
			this.name = field.getName();
			this.owner = field.getDeclaringClass().getName();
			this.isStatic = Modifier.isStatic(field.getModifiers());
		}
	}

	private static final Comparator<FieldSlot> BY_NAME = new Comparator<FieldSlot>() {
		@Override
		public int compare(FieldSlot a, FieldSlot b) {
			int c = a.name.compareTo(b.name);
			return c != 0 ? c : a.owner.compareTo(b.owner);
		}
	};

	private static final Field[] NO_FIELDS = new Field[0];

	final Class<?> cls;
	/** Non-final static fields declared by {@link #cls} itself. */
	final FieldSlot[] statics;
	/** Instance fields of {@link #cls} and its game superclasses, superclass first. */
	final FieldSlot[] instance;
	/** Reference-typed (non String) instance fields across the game hierarchy, for graph walks. */
	final Field[] instanceRefs;
	/** Reference-typed static fields declared by {@link #cls} (final ones included). */
	final Field[] staticRefs;
	final int instanceImageSize;
	final int staticImageSize;
	private final EnumMap<ValueType, int[]> acceptingInstance = new EnumMap<>(ValueType.class);
	private final EnumMap<ValueType, int[]> acceptingStatic = new EnumMap<>(ValueType.class);

	ClassInfo(Class<?> cls, RootSource roots) {
		this.cls = cls;
		List<FieldSlot> st = new ArrayList<>();
		List<Field> stRefs = new ArrayList<>();
		for (Field f : declaredFields(cls)) {
			if (!Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			ValueType t = ValueType.fromClass(f.getType());
			if (t != null) {
				if (!Modifier.isFinal(f.getModifiers()) && makeAccessible(f)) {
					st.add(new FieldSlot(f, t));
				}
			} else if (!f.getType().isPrimitive() && makeAccessible(f)) {
				stRefs.add(f);
			}
		}
		Collections.sort(st, BY_NAME);
		statics = st.toArray(new FieldSlot[0]);
		staticRefs = stRefs.toArray(new Field[0]);

		// hierarchy, superclass first, stopping at the first class that is not part of the game
		List<Class<?>> chain = new ArrayList<>();
		for (Class<?> c = cls; c != null && roots.isAppClass(c); c = c.getSuperclass()) {
			chain.add(0, c);
		}
		List<FieldSlot> inst = new ArrayList<>();
		List<Field> instRefs = new ArrayList<>();
		for (Class<?> c : chain) {
			List<FieldSlot> level = new ArrayList<>();
			for (Field f : declaredFields(c)) {
				if (Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				ValueType t = ValueType.fromClass(f.getType());
				if (t != null) {
					if (makeAccessible(f)) {
						level.add(new FieldSlot(f, t));
					}
				} else if (!f.getType().isPrimitive() && makeAccessible(f)) {
					instRefs.add(f);
				}
			}
			Collections.sort(level, BY_NAME);
			inst.addAll(level);
		}
		instance = inst.toArray(new FieldSlot[0]);
		instanceRefs = instRefs.toArray(new Field[0]);
		instanceImageSize = layoutImage(instance);
		staticImageSize = layoutImage(statics);
	}

	private static int layoutImage(FieldSlot[] slots) {
		int off = 0;
		for (FieldSlot s : slots) {
			if (s.type == ValueType.STRING) {
				s.imageOffset = -1;
			} else {
				s.imageOffset = off;
				off += s.type.width();
			}
		}
		return off;
	}

	private static Field[] declaredFields(Class<?> c) {
		try {
			return c.getDeclaredFields();
		} catch (LinkageError | RuntimeException e) {
			// a field type that cannot be resolved must not take the debugger down
			return NO_FIELDS;
		}
	}

	private static boolean makeAccessible(Field f) {
		try {
			f.setAccessible(true);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Slot indices a scan of {@code type} visits, cached. */
	synchronized int[] accepting(boolean staticFields, ValueType type) {
		EnumMap<ValueType, int[]> cache = staticFields ? acceptingStatic : acceptingInstance;
		int[] cached = cache.get(type);
		if (cached != null) {
			return cached;
		}
		FieldSlot[] slots = staticFields ? statics : instance;
		int[] tmp = new int[slots.length];
		int n = 0;
		for (int i = 0; i < slots.length; i++) {
			if (type.accepts(slots[i].type)) {
				tmp[n++] = i;
			}
		}
		int[] result = Arrays.copyOf(tmp, n);
		cache.put(type, result);
		return result;
	}

	/** Index of a field by name (and optionally declaring class), or -1. Most derived first. */
	int indexOf(boolean staticFields, String owner, String name) {
		FieldSlot[] slots = staticFields ? statics : instance;
		for (int i = slots.length - 1; i >= 0; i--) {
			FieldSlot s = slots[i];
			if (s.name.equals(name) && (owner == null || s.owner.equals(owner))) {
				return i;
			}
		}
		return -1;
	}

	/** Reference field lookup for path navigation, most derived class first. */
	Field refField(boolean staticFields, String owner, String name) {
		Field[] fields = staticFields ? staticRefs : instanceRefs;
		Field found = null;
		for (Field f : fields) {
			if (f.getName().equals(name) && (owner == null || f.getDeclaringClass().getName().equals(owner))) {
				found = f;
				if (owner != null) {
					break;
				}
			}
		}
		return found;
	}

	boolean hasStatics() {
		return statics.length > 0;
	}
}
