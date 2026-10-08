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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the work folder into one zip stream. Plain {@code java.io}: the caller supplies the
 * stream (a Storage Access Framework document in the app), so it works the same everywhere.
 */
public final class BackupWriter {
	public static final String INFO_ENTRY = "backup-info.json";
	private static final int BUFFER_SIZE = 64 * 1024;
	private static final int MAX_DEPTH = 32;

	private BackupWriter() {
	}

	/**
	 * Writes the backup to {@code out}; does not close it.
	 *
	 * @throws BackupProgress.Cancelled if {@code progress} was cancelled (the output is then incomplete)
	 */
	public static void write(File root, OutputStream out, BackupScope scope, BackupInfo info,
							 BackupProgress progress) throws IOException {
		ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out, BUFFER_SIZE));
		byte[] json = new Gson().toJson(info).getBytes("UTF-8");
		zip.putNextEntry(new ZipEntry(INFO_ENTRY));
		zip.write(json);
		zip.closeEntry();
		byte[] buf = new byte[BUFFER_SIZE];
		for (String dir : scope.dirs()) {
			addTree(zip, new File(root, dir), dir, buf, progress, 0);
		}
		zip.finish();
		zip.flush();
	}

	private static void addTree(ZipOutputStream zip, File file, String name, byte[] buf,
								BackupProgress progress, int depth) throws IOException {
		progress.checkCancelled();
		if (depth > MAX_DEPTH) {
			return;
		}
		if (file.isDirectory()) {
			if (file.getName().equals(".tmp")) {
				return; // an unfinished game installation
			}
			String[] names = file.list();
			if (names == null) {
				throw new IOException("Can't read folder " + file);
			}
			Arrays.sort(names);
			for (String child : names) {
				addTree(zip, new File(file, child), name + "/" + child, buf, progress, depth + 1);
			}
			return;
		}
		if (!file.isFile() || file.getName().equals(".nomedia")) {
			return;
		}
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(file.lastModified());
		zip.putNextEntry(entry);
		try (InputStream in = new FileInputStream(file)) {
			int n;
			while ((n = in.read(buf)) > 0) {
				zip.write(buf, 0, n);
				progress.addBytes(n);
				progress.checkCancelled();
			}
		}
		zip.closeEntry();
		progress.fileDone();
	}
}
