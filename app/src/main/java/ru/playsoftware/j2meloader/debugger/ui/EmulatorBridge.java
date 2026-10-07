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

package ru.playsoftware.j2meloader.debugger.ui;

import android.os.Looper;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.shell.AppClassLoader;
import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletThread;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.debugger.DebuggerStore;
import ru.playsoftware.j2meloader.debugger.MemoryDebugger;
import ru.playsoftware.j2meloader.debugger.PauseGate;
import ru.playsoftware.j2meloader.debugger.RootSource;

/**
 * Connects the debugger core to the emulator: it tells the debugger which objects are the roots
 * of the game and which classes belong to it. It is the only debugger class that knows about
 * {@link MicroActivity}, {@link MidletThread} and {@link AppClassLoader}.
 */
public final class EmulatorBridge implements RootSource {
	/** Packages of the platform and of the emulator itself; never part of the game. */
	private static final String[] NOT_THE_GAME = {
			"java.", "javax.", "android.", "androidx.", "dalvik.", "kotlin.", "com.android.", "com.google.",
			"org.microemu.", "ru.playsoftware.", "com.nokia.", "com.siemens.", "com.samsung.",
			"com.motorola.", "com.vodafone.", "com.mascotcapsule.", "com.jblend.", "com.sonyericsson.",
			"com.sprintpcs.", "com.kddi.", "mmpp.", "com.sun.",
	};

	private EmulatorBridge() {
	}

	/**
	 * Starts the debugger for the game at {@code appPath}. Call once per game start, before the
	 * MIDlet is loaded, and only when the master switch is on.
	 */
	public static void install(MicroActivity activity, String appPath) {
		String appId = new File(appPath).getName();
		File dir = BuildConfig.FULL_EMULATOR
				? new File(Config.getEmulatorDir(), "debugger")
				: new File(activity.getFilesDir(), "debugger");
		MemoryDebugger.install(new EmulatorBridge(), new DebuggerStore(new File(dir, appId + ".json"), appId));
		// the UI thread must never be parked by the pause gate
		PauseGate.setExemptThread(Looper.getMainLooper().getThread());
	}

	@Override
	public Map<String, Object> namedRoots() {
		Map<String, Object> roots = new LinkedHashMap<>(2);
		MIDlet midlet = MidletThread.getMidlet();
		if (midlet != null) {
			roots.put("midlet", midlet);
		}
		try {
			MicroActivity activity = ContextHolder.getActivity();
			Displayable current = activity == null ? null : activity.getCurrent();
			if (current != null) {
				roots.put("displayable", current);
			}
		} catch (RuntimeException e) {
			// no activity yet (or any more): the MIDlet root is enough
		}
		return roots;
	}

	@Override
	public boolean isAppClass(Class<?> c) {
		ClassLoader loader = c.getClassLoader();
		if (loader == null) {
			return false; // boot classes
		}
		if (BuildConfig.FULL_EMULATOR) {
			// game classes are the ones defined by the per-game DEX class loader
			return loader == AppClassLoader.getInstance();
		}
		String name = c.getName();
		for (String prefix : NOT_THE_GAME) {
			if (name.startsWith(prefix)) {
				return false;
			}
		}
		return true;
	}
}
