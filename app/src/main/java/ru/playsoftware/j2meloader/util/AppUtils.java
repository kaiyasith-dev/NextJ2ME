/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2022 Arman Jussupgaliyev
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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.woesss.j2me.jar.Descriptor;

public class AppUtils {
	private static final String TAG = AppUtils.class.getSimpleName();

	private static ArrayList<AppItem> getAppsList(@NonNull List<String> games) {
		ArrayList<AppItem> apps = new ArrayList<>();
		GamePaths paths = Config.getGamePaths();
		for (String game : games) {
			File appFolder = paths.appDir(game);
			File dex = new File(appFolder, Config.MIDLET_DEX_FILE);
			if (!dex.isFile()) {
				// a broken install: only the game's files go, its saves and settings stay
				FileUtils.deleteDirectory(appFolder);
				continue;
			}
			try {
				AppItem item = getApp(appFolder);
				apps.add(item);
			} catch (Exception e) {
				Log.w(TAG, "getAppsList: ", e);
				FileUtils.deleteDirectory(appFolder);
			}
		}
		return apps;
	}

	private static AppItem getApp(File appDir) throws IOException {
		File mf = new File(appDir, Config.MIDLET_MANIFEST_FILE);
		Descriptor params = new Descriptor(mf, false);
		AppItem item = new AppItem(GamePaths.gameOf(appDir), params.getName(),
				params.getVendor(),
				params.getVersion());
		File icon = new File(appDir, Config.MIDLET_ICON_FILE);
		if (icon.exists()) {
			item.setImagePathExt(Config.MIDLET_ICON_FILE);
		} else {
			String iconPath = Config.MIDLET_RES_DIR + '/' + params.getIcon();
			icon = new File(appDir, iconPath);
			if (icon.exists()) {
				item.setImagePathExt(iconPath);
			}
		}
		return item;
	}

	public static AppItem findApp(String name, String vendor, String uid) throws IOException {
		GamePaths paths = Config.getGamePaths();
		for (String game : paths.installedGames()) {
			File appDir = paths.appDir(game);
			File dex = new File(appDir, Config.MIDLET_DEX_FILE);
			if (!dex.isFile()) {
				FileUtils.deleteDirectory(appDir);
				continue;
			}
			try {
				File mf = new File(appDir, Config.MIDLET_MANIFEST_FILE);
				Descriptor params = new Descriptor(mf, false);
				if((uid != null && params.getNokiaUID() != null && params.getNokiaUID().equalsIgnoreCase(uid)) ||
						(name != null && params.getName().equalsIgnoreCase(name) &&
						(vendor == null || params.getVendor().equalsIgnoreCase(vendor)))
				) {
					AppItem item = new AppItem(game, params.getName(),
							params.getVendor(),
							params.getVersion());
					return item;
				}
			} catch (Exception e) {
			}
		}
		return null;
	}

	/** The save slots of the games in the current work folder. */
	public static SaveSlots saveSlots() {
		return new SaveSlots(new File(Config.getEmulatorDir()));
	}

	/** The folders with the user's data of a game: its saves (every slot), settings and debugger data. */
	public static File[] getUserDataDirs(AppItem item) {
		GamePaths paths = Config.getGamePaths();
		return new File[]{paths.savesDir(item.getPath()), paths.configDir(item.getPath()),
				paths.debuggerDir(item.getPath())};
	}

	/** Size of the saves in the slot the game uses (the other slots are not counted). */
	public static long getActiveSaveSize(AppItem item) {
		SaveSlots slots = saveSlots();
		return slots.size(item.getPath(), slots.active(item.getPath()));
	}

	/** Deletes the saved data of the game's active slot; the slot, the other slots, the game and its settings stay. */
	public static boolean clearData(AppItem item) {
		SaveSlots slots = saveSlots();
		String slot = slots.active(item.getPath());
		slots.clear(item.getPath(), slot);
		return slots.isEmpty(item.getPath(), slot);
	}

	/** Deletes the game with everything that belongs to it: files, saves, settings. */
	public static void deleteApp(AppItem item) {
		FileUtils.deleteDirectory(Config.getGamePaths().gameDir(item.getPath()));
	}

	public static void updateDb(AppRepository appRepository, List<AppItem> items) {
		GamePaths paths = Config.getGamePaths();
		for (String game : paths.allGameFolders()) {
			paths.cleanUp(game);
		}
		List<String> installed = paths.installedGames();
		if (installed.isEmpty()) {
			// If db isn't empty
			if (items.size() != 0) {
				appRepository.deleteAll();
			}
			return;
		}
		List<String> appFoldersList = new ArrayList<>(installed);
		// Delete invalid app items from db
		ListIterator<AppItem> iterator = items.listIterator(items.size());
		while (iterator.hasPrevious()) {
			AppItem item = iterator.previous();
			if (appFoldersList.remove(item.getPath())) {
				iterator.remove();
			}
		}
		if (items.size() > 0) {
			appRepository.delete(items);
		}
		if (appFoldersList.size() > 0) {
			appRepository.insert(getAppsList(appFoldersList));
		}
	}

	public static Bitmap getIconBitmap(AppItem appItem) {
		String file = appItem.getImagePathExt();
		if (file == null) {
			return null;
		}
		return BitmapFactory.decodeFile(file);
	}
}
