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

package ru.playsoftware.j2meloader.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class BuiltInShadersTest {
	private static final String ASSETS = "src/main/assets/";

	private static File asset(String name) {
		assertTrue(name, name.startsWith(ShaderInfo.ASSET_PREFIX));
		return new File(ASSETS + name.substring(ShaderInfo.ASSET_PREFIX.length()));
	}

	@Test
	public void everyBuiltInShaderHasItsFiles() {
		for (ShaderInfo s : ShaderInfo.builtIns()) {
			assertNotNull(s.vertex);
			assertNotNull(s.fragment);
			assertTrue(s + " vertex " + asset(s.vertex), asset(s.vertex).isFile());
			assertTrue(s + " fragment " + asset(s.fragment), asset(s.fragment).isFile());
		}
	}

	@Test
	public void namesAreDistinctSoTheSavedChoiceFindsTheRightOne() {
		Set<ShaderInfo> seen = new HashSet<>();
		Set<String> names = new HashSet<>();
		for (ShaderInfo s : ShaderInfo.builtIns()) {
			assertTrue(s.toString(), names.add(s.toString()));
			assertTrue(s.toString(), seen.add(s));
		}
	}

	@Test
	public void everySliderHasASaneRange() {
		for (ShaderInfo s : ShaderInfo.builtIns()) {
			int count = 0;
			boolean gap = false;
			for (ShaderInfo.Setting setting : s.settings) {
				if (setting == null) {
					gap = true;
					continue;
				}
				assertTrue(s + ": settings must be filled from the first slot", !gap);
				count++;
				assertNotNull(setting.name);
				assertTrue(s + " " + setting.name, setting.min < setting.max);
				assertTrue(s + " " + setting.name, setting.def >= setting.min && setting.def <= setting.max);
				assertTrue(s + " " + setting.name, setting.step > 0);
				assertTrue(s + " " + setting.name, setting.step <= setting.max - setting.min);
			}
			assertTrue(s + " has sliders", count > 0);
		}
	}

	@Test
	public void theShaderOnlyReadsTheSlidersItHas() {
		for (ShaderInfo s : ShaderInfo.builtIns()) {
			int sliders = 0;
			for (ShaderInfo.Setting setting : s.settings) {
				if (setting != null) sliders++;
			}
			String code = readAll(asset(s.fragment));
			String[] axes = {"x", "y", "z", "w"};
			for (int i = 0; i < 4; i++) {
				boolean used = code.contains("u_setting." + axes[i]);
				assertEquals(s + " u_setting." + axes[i], i < sliders, used);
			}
		}
	}

	private static String readAll(File f) {
		try {
			return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}
}
