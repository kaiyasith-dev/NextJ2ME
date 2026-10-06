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
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the real state of the running game.
 * <p>
 * J2ME-Loader has no VM of its own: the game is converted to DEX and runs as ordinary Java
 * objects on ART. The inspector therefore walks that live object graph through reflection,
 * starting from well defined roots (static reference fields of the game's classes, the MIDlet
 * and the current displayable). It only ever follows game classes plus plain
 * {@code java.util} containers, never framework internals, and it never exposes host
 * identities: objects get ids from the {@link ObjectRegistry}.
 */
public final class VmInspector {
	/** Upper bound of objects visited in one walk. */
	public static final int DEFAULT_MAX_OBJECTS = 250_000;

	public enum WalkKind {OBJECTS, ARRAYS, RAW_ARRAYS}

	/** Receives regions found by a walk; return false to stop early. */
	public interface RegionSink {
		boolean accept(MemoryRegion region);
	}

	/** A resolved {@link MemoryReference}: the region plus the slot inside it. */
	public static final class Target {
		public final MemoryRegion region;
		public final int slot;

		Target(MemoryRegion region, int slot) {
			this.region = region;
			this.slot = slot;
		}
	}

	/** Root part and steps of a path found by {@link #findPath}. */
	public static final class PathResult {
		public final String rootClass;
		public final String rootName;
		public final List<MemoryReference.Step> steps;

		PathResult(String rootClass, String rootName, List<MemoryReference.Step> steps) {
			this.rootClass = rootClass;
			this.rootName = rootName;
			this.steps = steps;
		}
	}

	private interface ChildSink {
		/** @param step navigation step, or null when the edge cannot be expressed as a path. */
		void child(Object value, MemoryReference.Step step);
	}

	private interface RootSink {
		void root(Object value, String rootClass, String rootName);
	}

	private final RootSource roots;
	private final ObjectRegistry registry;
	private final ConcurrentHashMap<Class<?>, ClassInfo> infos = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, WeakReference<Class<?>>> known = new ConcurrentHashMap<>();

	public VmInspector(RootSource roots, ObjectRegistry registry) {
		this.roots = roots;
		this.registry = registry;
	}

	public ObjectRegistry registry() {
		return registry;
	}

	// ---------------------------------------------------------------- classes

	/** Remembers a game class so its static fields can be scanned. Ignores non-game classes. */
	public void registerClass(Class<?> c) {
		if (known.containsKey(c.getName())) {
			return; // registered together with its game superclasses
		}
		for (Class<?> k = c; k != null && !k.isArray() && roots.isAppClass(k); k = k.getSuperclass()) {
			if (!known.containsKey(k.getName())) {
				known.put(k.getName(), new WeakReference<Class<?>>(k));
			}
		}
	}

	public Class<?> findKnownClass(String name) {
		WeakReference<Class<?>> ref = known.get(name);
		return ref == null ? null : ref.get();
	}

	/** Like {@link #findKnownClass} but also considers the classes of the root objects. */
	private Class<?> knownClassOrSeed(String name) {
		Class<?> c = findKnownClass(name);
		if (c == null) {
			seedFromRoots();
			c = findKnownClass(name);
		}
		return c;
	}

	/** Known game classes sorted by name. */
	public List<Class<?>> knownClasses() {
		List<Class<?>> out = new ArrayList<>(known.size());
		for (WeakReference<Class<?>> ref : known.values()) {
			Class<?> c = ref.get();
			if (c != null) {
				out.add(c);
			}
		}
		Collections.sort(out, new java.util.Comparator<Class<?>>() {
			@Override
			public int compare(Class<?> a, Class<?> b) {
				return a.getName().compareTo(b.getName());
			}
		});
		return out;
	}

	/** Makes sure the classes of the root objects are known. */
	public void seedFromRoots() {
		Map<String, Object> named = roots.namedRoots();
		for (Object o : named.values()) {
			if (o != null) {
				registerClass(o.getClass());
			}
		}
	}

	public void clear() {
		known.clear();
		infos.clear();
	}

	ClassInfo info(Class<?> c) {
		ClassInfo ci = infos.get(c);
		if (ci == null) {
			ci = new ClassInfo(c, roots);
			ClassInfo prev = infos.putIfAbsent(c, ci);
			if (prev != null) {
				ci = prev;
			}
		}
		return ci;
	}

	// ---------------------------------------------------------------- regions

	/** Region for the static fields of {@code c}, or null if it has none. */
	public MemoryRegion staticRegion(Class<?> c) {
		ClassInfo ci = info(c);
		if (!ci.hasStatics()) {
			return null;
		}
		return new FieldRegion(registry, ci);
	}

	/**
	 * Region for an arbitrary live object: a class (statics), a primitive array or a game
	 * object. Returns null for anything the debugger does not inspect.
	 */
	public MemoryRegion regionOf(Object o, boolean rawArrays) {
		if (o == null) {
			return null;
		}
		if (o instanceof Class) {
			return staticRegion((Class<?>) o);
		}
		Class<?> c = o.getClass();
		if (c.isArray()) {
			if (c.getComponentType().isPrimitive() && java.lang.reflect.Array.getLength(o) > 0) {
				return new ArrayRegion(registry, o, rawArrays);
			}
			return null;
		}
		if (!roots.isAppClass(c)) {
			return null;
		}
		ClassInfo ci = info(c);
		if (ci.instance.length == 0) {
			return null;
		}
		registerClass(c);
		return new FieldRegion(registry, ci, o);
	}

	// ---------------------------------------------------------------- walking

	private void forEachRoot(RootSink sink) {
		Map<String, Object> named = roots.namedRoots();
		for (Map.Entry<String, Object> e : named.entrySet()) {
			if (e.getValue() != null) {
				sink.root(e.getValue(), null, e.getKey());
			}
		}
		for (Class<?> c : knownClasses()) {
			ClassInfo ci = info(c);
			for (Field f : ci.staticRefs) {
				Object v = readStatic(f);
				if (v != null) {
					sink.root(v, c.getName(), f.getName());
				}
			}
		}
	}

	private static Object readStatic(Field f) {
		try {
			return f.get(null);
		} catch (Exception | LinkageError e) {
			return null;
		}
	}

	private void expand(Object o, boolean pathsOnly, ChildSink sink) {
		Class<?> c = o.getClass();
		try {
			if (c.isArray()) {
				if (!c.getComponentType().isPrimitive()) {
					Object[] arr = (Object[]) o;
					for (int i = 0; i < arr.length; i++) {
						Object v = arr[i];
						if (v != null) {
							sink.child(v, MemoryReference.Step.index(i));
						}
					}
				}
			} else if (roots.isAppClass(c)) {
				ClassInfo ci = info(c);
				for (Field f : ci.instanceRefs) {
					Object v = f.get(o);
					if (v != null) {
						sink.child(v, MemoryReference.Step.field(f.getDeclaringClass().getName(), f.getName()));
					}
				}
			} else if (o instanceof List && isJavaUtil(c)) {
				Object[] arr = SafeCalls.toArray(o);
				if (arr != null) {
					for (int i = 0; i < arr.length; i++) {
						if (arr[i] != null) {
							sink.child(arr[i], MemoryReference.Step.index(i));
						}
					}
				}
			} else if (!pathsOnly && isJavaUtil(c) && (o instanceof Map || o instanceof Collection)) {
				Object[] arr = SafeCalls.toArray(o);
				if (arr != null) {
					for (Object v : arr) {
						if (v != null) {
							sink.child(v, null);
						}
					}
				}
			}
		} catch (Exception | LinkageError e) {
			// the game keeps running while we look; a half updated structure is simply skipped
		}
	}

	private static boolean isJavaUtil(Class<?> c) {
		return c.getName().startsWith("java.util.");
	}

	/**
	 * Breadth first walk over the game's object graph, emitting a region for every game object
	 * (OBJECTS) or primitive array (ARRAYS / RAW_ARRAYS). Safe against concurrent mutation.
	 */
	public void walk(final WalkKind kind, final int maxObjects, final CancelToken cancel,
					 final RegionSink sink) {
		final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
		final ArrayDeque<Object> queue = new ArrayDeque<>();
		final ChildSink children = new ChildSink() {
			@Override
			public void child(Object value, MemoryReference.Step step) {
				if (seen.put(value, Boolean.TRUE) == null) {
					queue.add(value);
				}
			}
		};
		seedFromRoots();
		forEachRoot(new RootSink() {
			@Override
			public void root(Object value, String rootClass, String rootName) {
				children.child(value, null);
			}
		});
		int visited = 0;
		while (!queue.isEmpty() && visited < maxObjects && !cancel.isCancelled()) {
			Object o = queue.poll();
			visited++;
			Class<?> c = o.getClass();
			MemoryRegion region = null;
			if (c.isArray()) {
				if (c.getComponentType().isPrimitive()) {
					if (kind != WalkKind.OBJECTS) {
						region = regionOf(o, kind == WalkKind.RAW_ARRAYS);
					}
				}
			} else if (roots.isAppClass(c)) {
				registerClass(c);
				if (kind == WalkKind.OBJECTS) {
					region = regionOf(o, false);
				}
			}
			if (region != null && !sink.accept(region)) {
				return;
			}
			expand(o, false, children);
		}
	}

	// ---------------------------------------------------------------- paths

	private static final class Edge {
		final Object parent;
		final MemoryReference.Step step;
		final String rootClass;
		final String rootName;

		Edge(Object parent, MemoryReference.Step step, String rootClass, String rootName) {
			this.parent = parent;
			this.step = step;
			this.rootClass = rootClass;
			this.rootName = rootName;
		}
	}

	/**
	 * Finds how {@code target} can be reached from a static field or a named root using only
	 * field and index steps. Returns null if there is no such path (for example when the object
	 * is only held in a Hashtable or by the framework).
	 */
	public PathResult findPath(Object target, int maxObjects, CancelToken cancel) {
		IdentityHashMap<Object, PathResult> found = findPaths(Collections.singletonList(target), maxObjects, cancel);
		return found.get(target);
	}

	/**
	 * Like {@link #findPath} for many targets at once with a single walk. Targets without a path
	 * are missing from the result.
	 */
	public IdentityHashMap<Object, PathResult> findPaths(List<Object> targets, int maxObjects,
														 final CancelToken cancel) {
		final IdentityHashMap<Object, Edge> edges = new IdentityHashMap<>();
		final ArrayDeque<Object> queue = new ArrayDeque<>();
		IdentityHashMap<Object, Boolean> wanted = new IdentityHashMap<>();
		for (Object t : targets) {
			if (t != null) {
				wanted.put(t, Boolean.TRUE);
			}
		}
		IdentityHashMap<Object, PathResult> result = new IdentityHashMap<>();
		seedFromRoots();
		forEachRoot(new RootSink() {
			@Override
			public void root(Object value, String rootClass, String rootName) {
				if (!edges.containsKey(value)) {
					edges.put(value, new Edge(null, null, rootClass, rootName));
					queue.add(value);
				}
			}
		});
		int visited = 0;
		while (!queue.isEmpty() && visited < maxObjects && !cancel.isCancelled()
				&& result.size() < wanted.size()) {
			final Object o = queue.poll();
			visited++;
			if (wanted.containsKey(o)) {
				result.put(o, buildPath(edges, o));
			}
			expand(o, true, new ChildSink() {
				@Override
				public void child(Object value, MemoryReference.Step step) {
					if (step != null && !edges.containsKey(value)) {
						edges.put(value, new Edge(o, step, null, null));
						queue.add(value);
					}
				}
			});
		}
		return result;
	}

	private static PathResult buildPath(IdentityHashMap<Object, Edge> edges, Object end) {
		ArrayList<MemoryReference.Step> steps = new ArrayList<>();
		Object cur = end;
		Edge e = edges.get(cur);
		while (e.parent != null) {
			steps.add(e.step);
			cur = e.parent;
			e = edges.get(cur);
		}
		Collections.reverse(steps);
		return new PathResult(e.rootClass, e.rootName, steps);
	}

	// ---------------------------------------------------------------- resolution

	/** Re-resolves a persistent reference against the live game; null if it is unavailable. */
	public Target resolve(MemoryReference ref) {
		try {
			switch (ref.kind) {
				case STATIC_FIELD: {
					Class<?> c = knownClassOrSeed(ref.rootClass);
					if (c == null) {
						return null;
					}
					MemoryRegion region = staticRegion(c);
					if (!(region instanceof FieldRegion)) {
						return null;
					}
					int idx = ((FieldRegion) region).indexOf(null, ref.rootName);
					return idx < 0 ? null : new Target(region, idx);
				}
				case PATH:
					return resolvePath(ref);
				default:
					return null;
			}
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	private Target resolvePath(MemoryReference ref) {
		Object cur;
		if (ref.rootClass != null) {
			Class<?> c = knownClassOrSeed(ref.rootClass);
			if (c == null) {
				return null;
			}
			Field f = info(c).refField(true, null, ref.rootName);
			cur = f == null ? null : readStatic(f);
		} else {
			cur = roots.namedRoots().get(ref.rootName);
		}
		int n = ref.stepCount();
		if (cur == null || n == 0) {
			return null;
		}
		for (int i = 0; i < n - 1; i++) {
			cur = follow(cur, ref.step(i));
			if (cur == null) {
				return null;
			}
		}
		MemoryReference.Step last = ref.step(n - 1);
		if (last.isIndex()) {
			if (!cur.getClass().isArray() || !cur.getClass().getComponentType().isPrimitive()) {
				return null;
			}
			if (last.index < 0 || last.index >= java.lang.reflect.Array.getLength(cur)) {
				return null;
			}
			return new Target(new ArrayRegion(registry, cur, false), last.index);
		}
		MemoryRegion region = regionOf(cur, false);
		if (!(region instanceof FieldRegion) || ((FieldRegion) region).isStaticRegion()) {
			return null;
		}
		int idx = ((FieldRegion) region).indexOf(last.owner, last.field);
		return idx < 0 ? null : new Target(region, idx);
	}

	private Object follow(Object cur, MemoryReference.Step step) {
		try {
			Class<?> c = cur.getClass();
			if (step.isIndex()) {
				if (c.isArray() && !c.getComponentType().isPrimitive()) {
					Object[] arr = (Object[]) cur;
					return step.index >= 0 && step.index < arr.length ? arr[step.index] : null;
				}
				if (cur instanceof List && isJavaUtil(c)) {
					return SafeCalls.listGet((List<?>) cur, step.index);
				}
				return null;
			}
			if (c.isArray() || !roots.isAppClass(c)) {
				return null;
			}
			Field f = info(c).refField(false, step.owner, step.field);
			return f == null ? null : f.get(cur);
		} catch (Exception | LinkageError e) {
			return null;
		}
	}
}
