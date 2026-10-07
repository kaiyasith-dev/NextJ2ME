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

/** Primitive arrays reachable from the roots, scanned element by element with their own type. */
public final class ArrayProvider implements MemoryProvider {
	private final VmInspector inspector;
	private final int maxObjects;

	public ArrayProvider(VmInspector inspector, int maxObjects) {
		this.inspector = inspector;
		this.maxObjects = maxObjects;
	}

	@Override
	public ScanScope scope() {
		return ScanScope.ARRAYS;
	}

	@Override
	public void enumerate(VmInspector.RegionSink sink, CancelToken cancel) {
		inspector.walk(VmInspector.WalkKind.ARRAYS, maxObjects, cancel, sink);
	}

	@Override
	public MemoryRegion resolve(long regionId) {
		Object o = inspector.registry().get(regionId);
		if (o == null || !o.getClass().isArray()) {
			return null;
		}
		return inspector.regionOf(o, false);
	}
}
