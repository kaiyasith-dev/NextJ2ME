/*
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
 * Thrown by regions when the thing they point at is gone (object collected, class missing,
 * field not accessible, index out of range). The debugger reports these as "Unavailable".
 */
public class UnavailableException extends RuntimeException {
	public UnavailableException(String message) {
		super(message);
	}

	public UnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		return this; // cheap: this is an expected, frequent condition
	}
}
