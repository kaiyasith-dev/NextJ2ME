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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;

/**
 * Hands out stable emulator-level object ids and maps them back to live objects.
 * <p>
 * Ids are plain counters, independent of host identity hash codes or addresses, so they stay
 * meaningful when the garbage collector moves objects. Objects are only referenced weakly:
 * the debugger never keeps game objects alive, and an id whose object was collected simply
 * resolves to null ("Unavailable").
 */
public final class ObjectRegistry {
	private static final class Key extends WeakReference<Object> {
		final int hash;
		final long id;

		Key(Object referent, ReferenceQueue<Object> queue, long id) {
			super(referent, queue);
			this.hash = System.identityHashCode(referent);
			this.id = id;
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Key)) {
				return false;
			}
			Object a = get();
			return a != null && a == ((Key) o).get();
		}
	}

	private final HashMap<Key, Key> byObject = new HashMap<>();
	private final HashMap<Long, Key> byId = new HashMap<>();
	private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
	private long next = 1;

	public synchronized long idOf(Object o) {
		purge();
		Key probe = new Key(o, null, 0);
		Key existing = byObject.get(probe);
		if (existing != null) {
			return existing.id;
		}
		long id = next++;
		Key key = new Key(o, queue, id);
		byObject.put(key, key);
		byId.put(id, key);
		return id;
	}

	/** The live object for {@code id}, or null if it is unknown or was collected. */
	public synchronized Object get(long id) {
		Key key = byId.get(id);
		return key == null ? null : key.get();
	}

	public synchronized int size() {
		purge();
		return byId.size();
	}

	public synchronized void clear() {
		byObject.clear();
		byId.clear();
		while (queue.poll() != null) {
			// drain
		}
	}

	private void purge() {
		Object ref;
		while ((ref = queue.poll()) != null) {
			Key key = (Key) ref;
			byObject.remove(key);
			byId.remove(key.id);
		}
	}
}
