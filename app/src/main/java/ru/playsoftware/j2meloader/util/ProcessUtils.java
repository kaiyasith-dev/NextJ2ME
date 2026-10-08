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

package ru.playsoftware.j2meloader.util;

import android.app.ActivityManager;
import android.content.Context;

import java.util.List;

public final class ProcessUtils {
	private ProcessUtils() {
	}

	/**
	 * Whether a game is running. Games run in their own process ({@code :midlet}), which is killed
	 * when the game closes, so the process existing means a game is open.
	 */
	public static boolean isMidletRunning(Context context) {
		ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		if (am == null) {
			return false;
		}
		List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
		if (processes == null) {
			return false;
		}
		String name = context.getPackageName() + ":midlet";
		for (ActivityManager.RunningAppProcessInfo p : processes) {
			if (name.equals(p.processName)) {
				return true;
			}
		}
		return false;
	}
}
