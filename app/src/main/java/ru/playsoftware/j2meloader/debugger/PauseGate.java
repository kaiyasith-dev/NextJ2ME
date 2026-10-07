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

package ru.playsoftware.j2meloader.debugger;

/**
 * Cooperative "pause the emulator" switch.
 * <p>
 * There is no VM to suspend, and suspending arbitrary Java threads is not possible on Android.
 * Instead the emulator calls {@link #checkpoint()} at the places every J2ME game passes through
 * constantly: when it repaints / flushes its canvas and when its event queue picks the next
 * event. While the gate is closed those threads park there, which freezes the game logic of
 * every game that renders or handles input (practically all of them).
 * <p>
 * When the debugger is not in use the gate is never closed and a checkpoint costs one volatile
 * read. The UI thread is exempt so the emulator can never deadlock itself.
 */
public final class PauseGate {
	private static final Object LOCK = new Object();
	private static volatile boolean paused;
	private static volatile Thread exempt;
	private static int holds;
	private static int parked;

	private PauseGate() {
	}

	/** Called from emulator hot paths. */
	public static void checkpoint() {
		if (paused) {
			park();
		}
	}

	private static void park() {
		Thread t = Thread.currentThread();
		if (t == exempt) {
			return;
		}
		synchronized (LOCK) {
			parked++;
			try {
				while (paused) {
					LOCK.wait(500);
				}
			} catch (InterruptedException e) {
				t.interrupt();
			} finally {
				parked--;
			}
		}
	}

	/** Closes the gate (counted: every hold needs a matching {@link #release()}). */
	public static void hold() {
		synchronized (LOCK) {
			holds++;
			paused = true;
		}
	}

	public static void release() {
		synchronized (LOCK) {
			if (holds > 0) {
				holds--;
			}
			if (holds == 0) {
				paused = false;
				LOCK.notifyAll();
			}
		}
	}

	/** Opens the gate unconditionally. */
	public static void releaseAll() {
		synchronized (LOCK) {
			holds = 0;
			paused = false;
			LOCK.notifyAll();
		}
	}

	public static boolean isPaused() {
		return paused;
	}

	/** Number of threads currently parked at a checkpoint. */
	public static int parkedThreads() {
		synchronized (LOCK) {
			return parked;
		}
	}

	/** Thread that is never parked (the Android UI thread). */
	public static void setExemptThread(Thread thread) {
		exempt = thread;
	}
}
