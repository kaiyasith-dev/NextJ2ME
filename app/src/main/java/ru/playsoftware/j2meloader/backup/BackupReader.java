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

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** Reads a backup made by {@link BackupWriter} and puts its files back into the work folder. */
public final class BackupReader {
	private static final int BUFFER_SIZE = 64 * 1024;
	private static final int MAX_INFO_BYTES = 64 * 1024;
	private static final String TEMP_SUFFIX = ".restore-tmp";

	private BackupReader() {
	}

	/** What a restore did. */
	public static final class Result {
		public final BackupInfo info;
		public final int files;
		public final long bytes;
		public final int skipped;

		Result(BackupInfo info, int files, long bytes, int skipped) {
			this.info = info;
			this.files = files;
			this.bytes = bytes;
			this.skipped = skipped;
		}
	}

	/** Reads and checks only the header, so the user can be asked before anything is written. */
	public static BackupInfo readInfo(InputStream in) throws IOException {
		ZipInputStream zin = new ZipInputStream(new BufferedInputStream(in, BUFFER_SIZE));
		return readInfo(zin);
	}

	private static BackupInfo readInfo(ZipInputStream zin) throws IOException {
		ZipEntry first;
		try {
			first = zin.getNextEntry();
		} catch (ZipException e) {
			throw new BackupException("Not a NextJ2ME backup (unreadable zip file)");
		}
		if (first == null || !BackupWriter.INFO_ENTRY.equals(first.getName())) {
			throw new BackupException("Not a NextJ2ME backup");
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ((n = zin.read(buf)) > 0) {
			bytes.write(buf, 0, n);
			if (bytes.size() > MAX_INFO_BYTES) {
				throw new BackupException("Not a NextJ2ME backup");
			}
		}
		BackupInfo info;
		try {
			info = new Gson().fromJson(bytes.toString("UTF-8"), BackupInfo.class);
		} catch (JsonParseException e) {
			throw new BackupException("Not a NextJ2ME backup");
		}
		if (info == null || !BackupInfo.TYPE.equals(info.type)) {
			throw new BackupException("Not a NextJ2ME backup");
		}
		if (info.format != BackupInfo.FORMAT) {
			throw new BackupException("This backup was made by a newer version of the app");
		}
		return info;
	}

	/**
	 * Restores into {@code root}, overwriting files with the same name. Each file is written to a
	 * temporary name first, so a failure never leaves a half-written file behind.
	 *
	 * @param expectedDataVersion the data layout this app uses; other layouts are refused
	 */
	public static Result restore(InputStream in, File root, int expectedDataVersion,
								 BackupProgress progress) throws IOException {
		ZipInputStream zin = new ZipInputStream(new BufferedInputStream(in, BUFFER_SIZE));
		BackupInfo info = readInfo(zin);
		if (info.dataVersion != expectedDataVersion) {
			throw new BackupException("This backup uses a different data layout (version "
					+ info.dataVersion + ", this app uses " + expectedDataVersion + ")");
		}
		String rootPath = root.getCanonicalPath() + File.separator;
		byte[] buf = new byte[BUFFER_SIZE];
		int files = 0;
		int skipped = 0;
		long total = 0;
		ZipEntry entry;
		while ((entry = zin.getNextEntry()) != null) {
			progress.checkCancelled();
			String name = entry.getName();
			if (!isRestorable(checkedParts(name))) {
				skipped++;
				continue;
			}
			File dest = new File(root, name);
			if (!dest.getCanonicalPath().startsWith(rootPath)) {
				throw new BackupException("Unsafe path in backup: " + name);
			}
			if (entry.isDirectory()) {
				if (!dest.isDirectory() && !dest.mkdirs()) {
					throw new IOException("Can't create folder " + dest);
				}
				continue;
			}
			total += writeFile(zin, dest, entry.getTime(), buf, progress);
			files++;
			progress.fileDone();
		}
		return new Result(info, files, total, skipped);
	}

	/** Whether a file of the backup belongs in a work folder: a shared folder or a part of a game. */
	private static boolean isRestorable(String[] parts) {
		if (BackupScope.GAMES.equals(parts[0])) {
			// games/<game>/<app|saves|config|debugger>/...; a game folder may not be hidden
			return parts.length >= 3 && !parts[1].startsWith(".")
					&& BackupScope.RESTORABLE_GAME_DIRS.contains(parts[2]);
		}
		return BackupScope.RESTORABLE_SHARED.contains(parts[0]);
	}

	/** Splits {@code name} into its folders; rejects absolute paths and ".." segments. */
	private static String[] checkedParts(String name) throws BackupException {
		if (name.isEmpty() || name.startsWith("/") || name.contains("\\")) {
			throw new BackupException("Unsafe path in backup: " + name);
		}
		String[] parts = name.split("/", -1);
		for (int i = 0; i < parts.length; i++) {
			String p = parts[i];
			boolean trailingSlash = i == parts.length - 1 && p.isEmpty() && i > 0;
			if (p.equals("..") || p.equals(".") || (p.isEmpty() && !trailingSlash)) {
				throw new BackupException("Unsafe path in backup: " + name);
			}
		}
		return parts;
	}

	private static long writeFile(ZipInputStream zin, File dest, long time, byte[] buf,
								  BackupProgress progress) throws IOException {
		File parent = dest.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IOException("Can't create folder " + parent);
		}
		if (dest.isDirectory()) {
			throw new BackupException("A folder is in the way of " + dest.getName());
		}
		File tmp = new File(parent, dest.getName() + TEMP_SUFFIX);
		long written = 0;
		boolean ok = false;
		try {
			try (FileOutputStream out = new FileOutputStream(tmp)) {
				int n;
				while ((n = zin.read(buf)) > 0) {
					out.write(buf, 0, n);
					written += n;
					progress.addBytes(n);
					progress.checkCancelled();
				}
			}
			if (dest.exists() && !dest.delete()) {
				throw new IOException("Can't replace " + dest);
			}
			if (!tmp.renameTo(dest)) {
				throw new IOException("Can't write " + dest);
			}
			ok = true;
		} finally {
			if (!ok) {
				//noinspection ResultOfMethodCallIgnored
				tmp.delete();
			}
		}
		if (time > 0) {
			//noinspection ResultOfMethodCallIgnored
			dest.setLastModified(time);
		}
		return written;
	}
}
