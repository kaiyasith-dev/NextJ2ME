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

/** Emulator settings for the Mascot Capsule 3D renderer (not part of the game-facing API). */
public final class Render3DSettings {
	private static volatile int supersampling = 1;

	private Render3DSettings() {
	}

	/**
	 * How many times bigger than the game screen the 3D scene is rendered before it is averaged down
	 * (1 = the normal size, nothing extra is done). Takes effect the next time a game binds the 3D
	 * target.
	 */
	public static void setSupersampling(int scale) {
		supersampling = Math.max(1, Math.min(scale, SuperSampler.MAX_SCALE));
	}

	static int getSupersampling() {
		return supersampling;
	}
}
