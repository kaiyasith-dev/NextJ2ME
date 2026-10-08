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

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;

/**
 * The screen resolution of a game, read for showing in the games list. Unlike
 * {@link ProfilesManager#loadConfig} this only reads: it never migrates or rewrites the file.
 */
public final class ScreenInfo {
	private static final Gson GSON = new Gson();

	/** Just the fields of config.json that are needed here. */
	private static final class Dto {
		@SerializedName("ScreenWidth") int screenWidth;
		@SerializedName("ScreenHeight") int screenHeight;
	}

	public final int width;
	public final int height;

	ScreenInfo(int width, int height) {
		this.width = width;
		this.height = height;
	}

	/** Reads {@code file} (a config.json), or returns null if it is missing or not readable. */
	@Nullable
	public static ScreenInfo read(File file) {
		try (Reader reader = new FileReader(file)) {
			Dto d = GSON.fromJson(reader, Dto.class);
			if (d == null || d.screenWidth <= 0 || d.screenHeight <= 0) {
				return null;
			}
			return new ScreenInfo(d.screenWidth, d.screenHeight);
		} catch (Exception e) {
			return null;
		}
	}

	/** For example "240×320". */
	public String resolution() {
		return width + "×" + height;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ScreenInfo)) {
			return false;
		}
		ScreenInfo s = (ScreenInfo) o;
		return width == s.width && height == s.height;
	}

	@Override
	public int hashCode() {
		return 31 * width + height;
	}
}
