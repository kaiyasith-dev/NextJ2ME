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

package ru.playsoftware.j2meloader.backup;

import java.io.IOException;

/** Shared between the worker thread and the UI: live counters and a cancel switch. */
public final class BackupProgress {
	private volatile boolean cancelled;
	private volatile int files;
	private volatile long bytes;

	public void cancel() {
		cancelled = true;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public int files() {
		return files;
	}

	public long bytes() {
		return bytes;
	}

	void fileDone() {
		files++;
	}

	void addBytes(int n) {
		bytes += n;
	}

	void checkCancelled() throws IOException {
		if (cancelled) {
			throw new Cancelled();
		}
	}

	/** Thrown when the user cancels; not an error. */
	public static final class Cancelled extends IOException {
		Cancelled() {
			super("Cancelled");
		}
	}
}
