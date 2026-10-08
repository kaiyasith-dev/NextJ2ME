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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves and loads what belongs to one game: watches, frozen values and scan settings.
 * <p>
 * Only references that are meaningful in a later run are written (static fields and paths from
 * static roots). Scan results, object ids and virtual addresses are session state and are
 * deliberately never persisted. The file is keyed by the stable application identifier the
 * emulator uses for the game (the name of its install directory).
 */
public final class DebuggerStore {
	private static final int VERSION = 1;
	private static final Charset UTF8 = Charset.forName("UTF-8");

	/** Plain description of a watch or freeze as stored on disk. */
	public static final class TargetSpec {
		public String name;
		public MemoryReference ref;
		public ValueType type;
		public boolean bigEndian = true;
		public StringEncoding encoding = StringEncoding.UTF8;
		public int length;
		public MemoryValue value;
		public boolean enabled;
	}

	/** Everything read from disk. */
	public static final class Loaded {
		public DebuggerSettings settings = new DebuggerSettings();
		public final List<TargetSpec> watches = new ArrayList<>();
		public final List<TargetSpec> freezes = new ArrayList<>();
	}

	// ------------------------------------------------------------------ DTOs

	static final class StepDto {
		@SerializedName("owner") String owner;
		@SerializedName("field") String field;
		@SerializedName("index") int index;
	}

	static final class RefDto {
		@SerializedName("kind") String kind;
		@SerializedName("rootClass") String rootClass;
		@SerializedName("rootName") String rootName;
		@SerializedName("steps") List<StepDto> steps;
	}

	static final class TargetDto {
		@SerializedName("name") String name;
		@SerializedName("ref") RefDto ref;
		@SerializedName("type") String type;
		@SerializedName("bigEndian") boolean bigEndian = true;
		@SerializedName("encoding") String encoding;
		@SerializedName("length") int length;
		@SerializedName("value") String value;
		@SerializedName("enabled") boolean enabled;
	}

	static final class SettingsDto {
		@SerializedName("scope") String scope;
		@SerializedName("type") String type;
		@SerializedName("mode") String mode;
		@SerializedName("value") String value;
		@SerializedName("bigEndian") boolean bigEndian = true;
		@SerializedName("alignment") int alignment;
		@SerializedName("encoding") String encoding;
		@SerializedName("group") boolean group;
		@SerializedName("groupWindow") int groupWindow = ScanParams.DEFAULT_GROUP_WINDOW;
		@SerializedName("groupOrdered") boolean groupOrdered;
		@SerializedName("fuzzy") boolean fuzzy;
		@SerializedName("freezePeriodMs") int freezePeriodMs = 100;
	}

	static final class FileDto {
		@SerializedName("version") int version = VERSION;
		@SerializedName("appId") String appId;
		@SerializedName("settings") SettingsDto settings;
		@SerializedName("watches") List<TargetDto> watches = new ArrayList<>();
		@SerializedName("freezes") List<TargetDto> freezes = new ArrayList<>();
	}

	private final File file;
	private final String appId;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * @param file  where the per-game data lives
	 * @param appId stable identifier of the game (written into the file for diagnostics)
	 */
	public DebuggerStore(File file, String appId) {
		this.file = file;
		this.appId = appId;
	}

	// ------------------------------------------------------------------ load

	public Loaded load() {
		Loaded out = new Loaded();
		if (file == null || !file.isFile()) {
			return out;
		}
		FileDto dto;
		try (InputStream in = new FileInputStream(file); Reader r = new java.io.InputStreamReader(in, UTF8)) {
			dto = gson.fromJson(r, FileDto.class);
		} catch (IOException | JsonParseException e) {
			quarantine();
			return out;
		}
		if (dto == null) {
			return out;
		}
		if (dto.settings != null) {
			applySettings(dto.settings, out.settings);
		}
		readTargets(dto.watches, out.watches);
		readTargets(dto.freezes, out.freezes);
		return out;
	}

	/** A corrupt file must not stop the debugger: keep it aside and start fresh. */
	private void quarantine() {
		File bad = new File(file.getParentFile(), file.getName() + ".corrupt");
		//noinspection ResultOfMethodCallIgnored
		bad.delete();
		//noinspection ResultOfMethodCallIgnored
		file.renameTo(bad);
	}

	private static void applySettings(SettingsDto s, DebuggerSettings out) {
		ScanParams p = out.scan;
		// the scan source is deliberately not restored: every launch starts on the default (Raw memory)
		p.type = enumOf(ValueType.class, s.type, p.type);
		p.mode = enumOf(ScanMode.class, s.mode, p.mode);
		p.value = s.value == null ? "" : s.value;
		p.bigEndian = s.bigEndian;
		p.alignment = Math.max(0, s.alignment);
		p.encoding = enumOf(StringEncoding.class, s.encoding, p.encoding);
		p.group = s.group;
		p.groupWindow = s.groupWindow < 1 ? ScanParams.DEFAULT_GROUP_WINDOW
				: Math.min(s.groupWindow, ScanParams.MAX_GROUP_WINDOW);
		p.groupOrdered = s.groupOrdered;
		p.fuzzy = s.fuzzy;
		out.freezePeriodMs = s.freezePeriodMs <= 0 ? 100 : s.freezePeriodMs;
	}

	private static void readTargets(List<TargetDto> in, List<TargetSpec> out) {
		if (in == null) {
			return;
		}
		for (TargetDto d : in) {
			TargetSpec spec = toSpec(d);
			if (spec != null) {
				out.add(spec);
			}
		}
	}

	private static TargetSpec toSpec(TargetDto d) {
		if (d == null || d.ref == null || d.type == null) {
			return null;
		}
		try {
			TargetSpec s = new TargetSpec();
			s.name = d.name == null ? "" : d.name;
			s.ref = refFromDto(d.ref);
			s.type = ValueType.valueOf(d.type);
			s.bigEndian = d.bigEndian;
			s.encoding = enumOf(StringEncoding.class, d.encoding, StringEncoding.UTF8);
			s.length = d.length;
			s.enabled = d.enabled;
			if (d.value != null) {
				s.value = MemoryValue.parse(s.type, d.value, s.encoding);
			}
			return s.ref == null ? null : s;
		} catch (RuntimeException e) {
			return null; // an entry we cannot understand is dropped, the rest still loads
		}
	}

	private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
		if (name == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	// ------------------------------------------------------------------ save

	public void save(DebuggerSettings settings, List<TargetSpec> watches, List<TargetSpec> freezes) throws IOException {
		if (file == null) {
			return;
		}
		FileDto dto = new FileDto();
		dto.appId = appId;
		SettingsDto s = new SettingsDto();
		ScanParams p = settings.scan;
		s.type = p.type.name();
		s.mode = p.mode.name();
		s.value = p.value;
		s.bigEndian = p.bigEndian;
		s.alignment = p.alignment;
		s.encoding = p.encoding.name();
		s.group = p.group;
		s.groupWindow = p.groupWindow;
		s.groupOrdered = p.groupOrdered;
		s.fuzzy = p.fuzzy;
		s.freezePeriodMs = settings.freezePeriodMs;
		dto.settings = s;
		writeTargets(watches, dto.watches);
		writeTargets(freezes, dto.freezes);
		writeAtomically(file, gson.toJson(dto));
	}

	private static void writeTargets(List<TargetSpec> in, List<TargetDto> out) {
		for (TargetSpec s : in) {
			if (s.ref == null || !s.ref.isPersistent()) {
				continue; // session state never reaches the disk
			}
			TargetDto d = new TargetDto();
			d.name = s.name;
			d.ref = refToDto(s.ref);
			d.type = s.type.name();
			d.bigEndian = s.bigEndian;
			d.encoding = s.encoding.name();
			d.length = s.length;
			d.value = s.value == null ? null : s.value.format();
			d.enabled = s.enabled;
			out.add(d);
		}
	}

	private static void writeAtomically(File target, String json) throws IOException {
		File dir = target.getParentFile();
		if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("Cannot create " + dir);
		}
		File tmp = new File(dir, target.getName() + ".tmp");
		try (OutputStream out = new FileOutputStream(tmp); Writer w = new OutputStreamWriter(out, UTF8)) {
			w.write(json);
		}
		if (!tmp.renameTo(target)) {
			// some file systems refuse to rename over an existing file
			//noinspection ResultOfMethodCallIgnored
			target.delete();
			if (!tmp.renameTo(target)) {
				throw new IOException("Cannot write " + target);
			}
		}
	}

	// ------------------------------------------------------------------ references

	static RefDto refToDto(MemoryReference ref) {
		RefDto d = new RefDto();
		d.kind = ref.kind.name();
		d.rootClass = ref.rootClass;
		d.rootName = ref.rootName;
		d.steps = new ArrayList<>();
		for (MemoryReference.Step st : ref.steps()) {
			StepDto sd = new StepDto();
			sd.owner = st.owner;
			sd.field = st.field;
			sd.index = st.index;
			d.steps.add(sd);
		}
		return d;
	}

	static MemoryReference refFromDto(RefDto d) {
		MemoryReference.Kind kind = enumOf(MemoryReference.Kind.class, d.kind, null);
		if (kind == null || !(kind == MemoryReference.Kind.STATIC_FIELD || kind == MemoryReference.Kind.PATH)
				|| d.rootName == null) {
			return null;
		}
		if (kind == MemoryReference.Kind.STATIC_FIELD) {
			return d.rootClass == null ? null : MemoryReference.staticField(d.rootClass, d.rootName);
		}
		List<MemoryReference.Step> steps = new ArrayList<>();
		if (d.steps != null) {
			for (StepDto sd : d.steps) {
				steps.add(sd.field == null ? MemoryReference.Step.index(sd.index)
						: MemoryReference.Step.field(sd.owner, sd.field));
			}
		}
		if (steps.isEmpty()) {
			return null;
		}
		MemoryReference.Step[] arr = steps.toArray(new MemoryReference.Step[0]);
		return d.rootClass != null ? MemoryReference.path(d.rootClass, d.rootName, arr)
				: MemoryReference.namedPath(d.rootName, arr);
	}
}
