/*
 * Copyright 2026 ksdevla
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

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reads game collections for the inspector. The game keeps running while it is scanned, so a
 * synchronized {@code Vector} or {@code Hashtable} is only ever locked for a moment and a plain
 * call is enough.
 */
final class SafeCalls {
	private SafeCalls() {
	}

	/** Elements of a {@code java.util} collection (map values), or null if unreadable. */
	static Object[] toArray(Object collection) {
		if (collection instanceof Map) {
			return ((Map<?, ?>) collection).values().toArray();
		}
		if (collection instanceof Collection) {
			return ((Collection<?>) collection).toArray();
		}
		return null;
	}

	/** Element {@code index} of a list, or null if out of range. */
	static Object listGet(List<?> list, int index) {
		return index >= 0 && index < list.size() ? list.get(index) : null;
	}
}
