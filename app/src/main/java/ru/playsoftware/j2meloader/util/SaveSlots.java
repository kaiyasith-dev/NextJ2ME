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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Several named save slots for each game, all in one folder per game.
 * <p>
 * A game keeps its saves (record stores and private files) in one folder, and a slot is such a
 * folder. The <em>default</em> slot is the game's saves folder itself,
 * {@code <work folder>/games/<game>/saves}; the slots the user made are in its {@code slots} subfolder,
 * {@code games/<game>/saves/slots/<name>}. The slot the game uses is written to
 * {@code games/<game>/saves/.active}. The game process reads it when it starts and uses that slot's
 * folder for everything it saves, so switching slots needs no copying.
 * <p>
 * Plain {@code java.io}: the emulator process and the main app both use it.
 */
public final class SaveSlots {
	/** Name of the default slot (the saves folder itself). Shown to the user in their language. */
	public static final String DEFAULT = "";

	private static final String SLOTS = "slots";
	private static final String ACTIVE_FILE = ".active";
	/** In the saves folder of a game, what belongs to the slots and not to the default slot's saves. */
	private static final Set<String> NOT_DEFAULT_SAVES = Collections.unmodifiableSet(
			new HashSet<>(Arrays.asList(SLOTS, ACTIVE_FILE)));
	private static final int MAX_NAME_LENGTH = 40;
	private static final int BUFFER_SIZE = 32 * 1024;

	private final GamePaths paths;

	/** @param workDir the emulator work folder (the one that holds {@code games}, {@code templates}...) */
	public SaveSlots(File workDir) {
		this.paths = new GamePaths(workDir);
	}

	// ------------------------------------------------------------------ folders

	/** All the saves of a game: the default slot's files, the {@code slots} folder and the choice. */
	public File savesDir(String game) {
		return paths.savesDir(game);
	}

	/** The folder that holds the slots the user made. */
	public File slotsDir(String game) {
		return new File(savesDir(game), SLOTS);
	}

	/** The folder of a slot; {@link #DEFAULT} (or null) is the default slot, the game's saves folder. */
	public File dir(String game, String slot) {
		if (slot == null || slot.isEmpty()) {
			return savesDir(game);
		}
		return new File(slotsDir(game), slot);
	}

	/** The folder the game must save to now. Not created. */
	public File activeDir(String game) {
		return dir(game, active(game));
	}

	// ------------------------------------------------------------------ the slots

	/** The slots the user made for a game, sorted by name. The default slot is not listed. */
	public List<String> list(String game) {
		File[] dirs = slotsDir(game).listFiles();
		List<String> names = new ArrayList<>();
		if (dirs != null) {
			for (File f : dirs) {
				if (f.isDirectory() && !f.getName().startsWith(".")) {
					names.add(f.getName());
				}
			}
		}
		Collections.sort(names, new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				int c = a.compareToIgnoreCase(b);
				return c != 0 ? c : a.compareTo(b);
			}
		});
		return names;
	}

	/** The slot the game uses; {@link #DEFAULT} if none was chosen or the chosen one is gone. */
	public String active(String game) {
		File file = new File(savesDir(game), ACTIVE_FILE);
		if (!file.isFile()) {
			return DEFAULT;
		}
		String name;
		try (InputStream in = new FileInputStream(file)) {
			byte[] buf = new byte[256];
			int n = in.read(buf);
			name = n <= 0 ? "" : new String(buf, 0, n, "UTF-8").trim();
		} catch (IOException e) {
			return DEFAULT;
		}
		if (name.isEmpty() || !isValidName(name) || !dir(game, name).isDirectory()) {
			return DEFAULT;
		}
		return name;
	}

	/** Makes {@code slot} the one the game uses from its next start. */
	public void setActive(String game, String slot) throws IOException {
		File root = savesDir(game);
		File file = new File(root, ACTIVE_FILE);
		if (slot == null || slot.isEmpty()) {
			if (file.exists() && !file.delete()) {
				throw new IOException("Can't update " + file);
			}
			return;
		}
		if (!dir(game, slot).isDirectory()) {
			throw new IOException("There is no slot \"" + slot + "\"");
		}
		File tmp = new File(root, ACTIVE_FILE + ".tmp");
		try (OutputStream out = new FileOutputStream(tmp)) {
			out.write(slot.getBytes("UTF-8"));
		}
		if (file.exists() && !file.delete()) {
			throw new IOException("Can't update " + file);
		}
		if (!tmp.renameTo(file)) {
			throw new IOException("Can't update " + file);
		}
	}

	/**
	 * Creates an empty slot.
	 *
	 * @return the name it was stored under (cleaned up)
	 * @throws IllegalArgumentException if the name is not usable or already taken
	 */
	public String create(String game, String name) throws IOException {
		String n = checkNew(game, name);
		File dir = new File(slotsDir(game), n);
		if (!dir.mkdirs()) {
			throw new IOException("Can't create " + dir);
		}
		return n;
	}

	/** Copies a slot ({@link #DEFAULT} for the default one) into a new one. */
	public String duplicate(String game, String from, String name) throws IOException {
		String n = checkNew(game, name);
		File src = dir(game, from);
		File root = slotsDir(game);
		if (!root.isDirectory() && !root.mkdirs()) {
			throw new IOException("Can't create " + root);
		}
		boolean fromDefault = from == null || from.isEmpty();
		// copied under a temporary name first, so a half-finished copy never looks like a slot
		File tmp = new File(root, "." + n + ".tmp");
		FileUtils.deleteDirectory(tmp);
		try {
			if (src.isDirectory()) {
				// the default slot is the saves folder itself: its slots and the choice are not part of it
				copyTree(src, tmp, fromDefault ? NOT_DEFAULT_SAVES : Collections.<String>emptySet());
			} else if (!tmp.mkdirs()) {
				throw new IOException("Can't create " + tmp);
			}
			if (!tmp.renameTo(new File(root, n))) {
				throw new IOException("Can't create slot \"" + n + "\"");
			}
		} catch (IOException e) {
			FileUtils.deleteDirectory(tmp);
			throw e;
		}
		return n;
	}

	/** Renames a slot (not the default one); keeps it active if it was. */
	public String rename(String game, String from, String name) throws IOException {
		if (from == null || from.isEmpty()) {
			throw new IllegalArgumentException("The default slot can not be renamed");
		}
		File src = dir(game, from);
		if (!src.isDirectory()) {
			throw new IOException("There is no slot \"" + from + "\"");
		}
		String n = normalize(name);
		if (!n.equalsIgnoreCase(from)) {
			checkNew(game, n);
		}
		boolean wasActive = from.equals(active(game));
		File dst = new File(slotsDir(game), n);
		if (!n.equals(from)) {
			if (n.equalsIgnoreCase(from)) {
				// only the case changes: go through a temporary name
				File tmp = new File(slotsDir(game), "." + n + ".rename");
				if (!src.renameTo(tmp) || !tmp.renameTo(dst)) {
					throw new IOException("Can't rename \"" + from + "\"");
				}
			} else if (!src.renameTo(dst)) {
				throw new IOException("Can't rename \"" + from + "\"");
			}
		}
		if (wasActive) {
			setActive(game, n);
		}
		return n;
	}

	/** Deletes a slot and its saves (not the default one). The game goes back to the default if it was active. */
	public void delete(String game, String slot) throws IOException {
		if (slot == null || slot.isEmpty()) {
			throw new IllegalArgumentException("The default slot can not be deleted");
		}
		boolean wasActive = slot.equals(active(game));
		if (wasActive) {
			setActive(game, DEFAULT);
		}
		File dir = dir(game, slot);
		FileUtils.deleteDirectory(dir);
		if (dir.exists()) {
			throw new IOException("Can't delete \"" + slot + "\" completely");
		}
	}

	/**
	 * Deletes the saves inside a slot but keeps the slot. For the default slot the other slots and
	 * the choice of the slot are left alone.
	 */
	public void clear(String game, String slot) {
		boolean isDefault = slot == null || slot.isEmpty();
		File[] children = dir(game, slot).listFiles();
		if (children == null) {
			return;
		}
		for (File c : children) {
			if (!isDefault || !NOT_DEFAULT_SAVES.contains(c.getName())) {
				FileUtils.deleteDirectory(c);
			}
		}
	}

	/** Whether a slot holds no saves (the other slots inside the default folder do not count). */
	public boolean isEmpty(String game, String slot) {
		boolean isDefault = slot == null || slot.isEmpty();
		String[] names = dir(game, slot).list();
		if (names == null) {
			return true;
		}
		for (String name : names) {
			if (!isDefault || !NOT_DEFAULT_SAVES.contains(name)) {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ the whole game

	/** Bytes used by the saves of one slot (for the default slot: without the other slots). */
	public long size(String game, String slot) {
		boolean isDefault = slot == null || slot.isEmpty();
		return sizeOf(dir(game, slot), isDefault ? NOT_DEFAULT_SAVES : Collections.<String>emptySet());
	}

	// ------------------------------------------------------------------ names

	/**
	 * Cleans up a name typed by the user: trimmed, without characters that are not allowed in file
	 * names, without leading dots, at most 40 characters.
	 *
	 * @throws IllegalArgumentException if nothing usable is left or it is the reserved name
	 */
	public static String normalize(String name) {
		String n = name == null ? "" : name.replaceAll(FileUtils.ILLEGAL_FILENAME_CHARS, "").trim();
		while (n.startsWith(".")) {
			n = n.substring(1).trim();
		}
		if (n.length() > MAX_NAME_LENGTH) {
			n = n.substring(0, MAX_NAME_LENGTH).trim();
		}
		if (n.isEmpty()) {
			throw new IllegalArgumentException("Enter a name for the slot");
		}
		if (n.equalsIgnoreCase("default")) {
			throw new IllegalArgumentException("\"Default\" is the name of the default slot");
		}
		return n;
	}

	private static boolean isValidName(String name) {
		try {
			return normalize(name).equals(name);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private String checkNew(String game, String name) {
		String n = normalize(name);
		for (String existing : list(game)) {
			if (existing.equalsIgnoreCase(n)) {
				throw new IllegalArgumentException("A slot named \"" + existing + "\" already exists");
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ files

	/** Bytes in {@code f}; entries of the top folder named in {@code skip} are not counted. */
	private static long sizeOf(File f, Set<String> skip) {
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			long total = 0;
			if (children != null) {
				for (File c : children) {
					if (!skip.contains(c.getName())) {
						total += sizeOf(c, Collections.<String>emptySet());
					}
				}
			}
			return total;
		}
		return f.isFile() ? f.length() : 0;
	}

	/** Copies {@code src} to {@code dst}; entries of the top folder named in {@code skip} are left out. */
	private static void copyTree(File src, File dst, Set<String> skip) throws IOException {
		if (src.isDirectory()) {
			if (!dst.isDirectory() && !dst.mkdirs()) {
				throw new IOException("Can't create " + dst);
			}
			String[] names = src.list();
			if (names == null) {
				throw new IOException("Can't read " + src);
			}
			Arrays.sort(names);
			for (String name : names) {
				if (!skip.contains(name)) {
					copyTree(new File(src, name), new File(dst, name), Collections.<String>emptySet());
				}
			}
			return;
		}
		try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
			byte[] buf = new byte[BUFFER_SIZE];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
		}
		//noinspection ResultOfMethodCallIgnored
		dst.setLastModified(src.lastModified());
	}

	/** For display: the name, or the given label for the default slot. */
	public static String label(String slot, String defaultLabel) {
		return slot == null || slot.isEmpty() ? defaultLabel : slot;
	}

	@Override
	public String toString() {
		return "SaveSlots(" + paths.gamesDir() + ")";
	}
}
