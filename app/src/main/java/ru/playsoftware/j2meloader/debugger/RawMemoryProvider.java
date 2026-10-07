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
 * The byte-addressable view: the big-endian images of all primitive arrays reachable from the
 * roots, laid out in the emulator's virtual {@link AddressSpace}. Any value type can be
 * decoded at any byte offset, like in a classic memory scanner. This is the closest thing
 * to "raw memory" a J2ME app has in J2ME-Loader; it is not the Android process memory.
 */
public final class RawMemoryProvider implements MemoryProvider {
	private final VmInspector inspector;
	private final AddressSpace space;
	private final int maxObjects;

	public RawMemoryProvider(VmInspector inspector, AddressSpace space, int maxObjects) {
		this.inspector = inspector;
		this.space = space;
		this.maxObjects = maxObjects;
	}

	@Override
	public ScanScope scope() {
		return ScanScope.RAW;
	}

	@Override
	public void enumerate(VmInspector.RegionSink sink, CancelToken cancel) {
		// addresses are assigned lazily, when a result or the viewer first needs one
		inspector.walk(VmInspector.WalkKind.RAW_ARRAYS, maxObjects, cancel, sink);
	}

	@Override
	public MemoryRegion resolve(long regionId) {
		Object o = inspector.registry().get(regionId);
		if (o == null || !o.getClass().isArray()) {
			return null;
		}
		return inspector.regionOf(o, true);
	}
}
