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

import java.nio.charset.Charset;

/** Text encodings for string scans of raw memory. */
public enum StringEncoding {
	UTF8("UTF-8", "UTF-8"),
	ASCII("ASCII / Latin-1", "ISO-8859-1"),
	UTF16BE("UTF-16 (big endian)", "UTF-16BE");

	private final String label;
	private final Charset charset;

	StringEncoding(String label, String charsetName) {
		this.label = label;
		this.charset = Charset.forName(charsetName);
	}

	public String label() {
		return label;
	}

	public byte[] encode(String text) {
		return text.getBytes(charset);
	}

	public String decode(byte[] bytes) {
		return new String(bytes, charset);
	}
}
