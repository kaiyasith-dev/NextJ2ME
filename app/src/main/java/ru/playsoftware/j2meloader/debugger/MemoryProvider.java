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

/**
 * A source of {@link MemoryRegion}s for one {@link ScanScope}. The scanner works purely against
 * this interface and knows nothing about reflection, classes or arrays.
 */
public interface MemoryProvider {
	ScanScope scope();

	/** Enumerates every region of this scope. The sink may stop the enumeration early. */
	void enumerate(VmInspector.RegionSink sink, CancelToken cancel);

	/** The region with the given stable id, or null if it is gone or not of this scope. */
	MemoryRegion resolve(long regionId);
}
