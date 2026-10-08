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

package com.mascotcapsule.micro3d.v3;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class Render3DSettingsTest {
	@After
	public void restore() {
		Render3DSettings.setSupersampling(1);
	}

	@Test
	public void theNormalSizeIsTheDefault() {
		assertEquals(1, Render3DSettings.getSupersampling());
	}

	@Test
	public void validMultiplesAreKept() {
		for (int s = 1; s <= SuperSampler.MAX_SCALE; s++) {
			Render3DSettings.setSupersampling(s);
			assertEquals(s, Render3DSettings.getSupersampling());
		}
	}

	@Test
	public void nonsenseValuesAreLimited() {
		Render3DSettings.setSupersampling(0);
		assertEquals(1, Render3DSettings.getSupersampling());
		Render3DSettings.setSupersampling(-5);
		assertEquals(1, Render3DSettings.getSupersampling());
		Render3DSettings.setSupersampling(99);
		assertEquals(SuperSampler.MAX_SCALE, Render3DSettings.getSupersampling());
	}
}
