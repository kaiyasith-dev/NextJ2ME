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

/** Static (non final) fields of the game's loaded classes. */
public final class StaticFieldProvider implements MemoryProvider {
	private final VmInspector inspector;

	public StaticFieldProvider(VmInspector inspector) {
		this.inspector = inspector;
	}

	@Override
	public ScanScope scope() {
		return ScanScope.STATIC_FIELDS;
	}

	@Override
	public void enumerate(VmInspector.RegionSink sink, CancelToken cancel) {
		inspector.seedFromRoots();
		for (Class<?> c : inspector.knownClasses()) {
			if (cancel.isCancelled()) {
				return;
			}
			MemoryRegion region = inspector.staticRegion(c);
			if (region != null && !sink.accept(region)) {
				return;
			}
		}
	}

	@Override
	public MemoryRegion resolve(long regionId) {
		Object o = inspector.registry().get(regionId);
		return o instanceof Class ? inspector.staticRegion((Class<?>) o) : null;
	}
}
