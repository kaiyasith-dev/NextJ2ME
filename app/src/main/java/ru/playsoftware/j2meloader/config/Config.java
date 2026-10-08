/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2022 Arman Jussupgaliyev
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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;

import java.io.File;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import androidx.preference.PreferenceManager;

import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;

import static ru.playsoftware.j2meloader.util.Constants.*;

import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.GamePaths;

public class Config {
	public static final String DEX_OPT_CACHE_DIR = "dex_opt";
	public static final String FS_DIR = "/fs/";
	public static final String MIDLET_CONFIG_FILE = "/config.json";
	public static final String MIDLET_DEX_FILE = "/converted.dex";
	public static final String MIDLET_ICON_FILE = "/icon.png";
	public static final String MIDLET_KEY_LAYOUT_FILE = "/VirtualKeyboardLayout";
	public static final String MIDLET_MANIFEST_FILE = MIDLET_DEX_FILE + ".conf";
	public static final String MIDLET_RES_DIR = "/res";
	public static final String MIDLET_RES_FILE = "/res.jar";
	public static final String SCREENSHOTS_DIR;
	public static final String SHADERS_DIR = "/shaders/";

	private static String emulatorDir;
	private static String profilesDir;
	private static GamePaths gamePaths;

	private static final SharedPreferences.OnSharedPreferenceChangeListener sPrefListener =
			(sharedPreferences, key) -> {
				if (key.equals(PREF_EMULATOR_DIR)) {
					initDirs(sharedPreferences.getString(key, emulatorDir));
				}
			};

	static {
		Context context = ContextHolder.getAppContext();
		String appName = "NextJ2ME";
		if (!BuildConfig.FULL_EMULATOR) {
			appName = context.getString(R.string.app_name);
		}
		SCREENSHOTS_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
				+ "/" + appName;
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		String path = FileUtils.isExternalStorageLegacy() ?
				preferences.getString(PREF_EMULATOR_DIR, null) :
				context.getExternalFilesDir(null).getPath();
		if (path == null) {
			path = Environment.getExternalStorageDirectory() + "/" + appName;
		}
		initDirs(path);
		preferences.registerOnSharedPreferenceChangeListener(sPrefListener);
	}

	public static String getEmulatorDir() {
		return emulatorDir;
	}

	public static String getProfilesDir() {
		return profilesDir;
	}

	/** Where the files of each game are (see {@link GamePaths}). */
	public static GamePaths getGamePaths() {
		return gamePaths;
	}

	public static String getShadersDir() {
		return emulatorDir + SHADERS_DIR;
	}

	public static String getFsInternalDir() {
		return emulatorDir + FS_DIR + "c/";
	}

	public static String getFsExternalDir() {
		if (FileUtils.isExternalStorageLegacy()) {
			return Environment.getExternalStorageDirectory().getPath() + "/";
		} else {
			return emulatorDir + FS_DIR + "e/";
		}
	}

	public static void startApp(Context context, String name, String path, boolean showSettings) {
		startApp(context, name, path, showSettings, null);
	}

	public static void startApp(Context context, String name, String path, boolean showSettings, String arguments) {
		File appDir = new File(path);
		File workDir = GamePaths.workDirOf(appDir);
		// a game that was never set up has no settings folder yet
		File file = workDir == null ? null : new GamePaths(workDir).configDir(GamePaths.gameOf(appDir));
		if (showSettings || file == null || !file.exists()) {
			Intent intent = new Intent(ACTION_EDIT, Uri.parse(path),
					context, ConfigActivity.class);
			intent.putExtra(KEY_MIDLET_NAME, name);
			intent.putExtra(KEY_START_ARGUMENTS, arguments);
			context.startActivity(intent);
		} else {
			Intent intent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(path),
					context, MicroActivity.class);
			intent.putExtra(KEY_MIDLET_NAME, name);
			intent.putExtra(KEY_START_ARGUMENTS, arguments);
			context.startActivity(intent);
		}
	}

	private static void initDirs(String path) {
		emulatorDir = path;
		profilesDir = emulatorDir + "/templates/";
		gamePaths = new GamePaths(new File(path));
	}
}
