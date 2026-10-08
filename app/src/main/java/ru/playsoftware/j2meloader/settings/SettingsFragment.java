/*
 * Copyright 2017-2018 Nikita Shakarun
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

package ru.playsoftware.j2meloader.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;

import com.nononsenseapps.filepicker.Utils;

import java.io.File;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.backup.BackupScope;
import ru.playsoftware.j2meloader.backup.BackupUi;
import ru.playsoftware.j2meloader.backup.CreateBackupContract;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfilesActivity;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.PickDirResultContract;
import ru.playsoftware.j2meloader.util.SAFFileResultContract;

import static ru.playsoftware.j2meloader.util.Constants.PREF_ADD_CUTOUT_AREA;
import static ru.playsoftware.j2meloader.util.Constants.PREF_EMULATOR_DIR;

public class SettingsFragment extends PreferenceFragmentCompat {
	private static final String STATE_BACKUP_SCOPE = "backup_scope";
	private Preference prefFolder;
	private BackupScope pendingBackupScope = BackupScope.SAVES;
	private final ActivityResultLauncher<String> backupLauncher = registerForActivityResult(
			new CreateBackupContract(),
			this::onBackupTarget);
	private final ActivityResultLauncher<String> restoreLauncher = registerForActivityResult(
			new SAFFileResultContract(),
			this::onRestoreSource);
	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult);

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) {
			try {
				pendingBackupScope = BackupScope.valueOf(savedInstanceState.getString(STATE_BACKUP_SCOPE));
			} catch (RuntimeException ignored) {
				// keep the default
			}
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString(STATE_BACKUP_SCOPE, pendingBackupScope.name());
	}

	@Override
	public void onCreatePreferences(Bundle bundle, String s) {
		addPreferencesFromResource(R.xml.preferences);
		findPreference("pref_default_settings").setIntent(new Intent(requireActivity(), ProfilesActivity.class));
		prefFolder = findPreference(PREF_EMULATOR_DIR);
		prefFolder.setSummary(Config.getEmulatorDir());
		prefFolder.setOnPreferenceClickListener(preference -> {
			if (FileUtils.isExternalStorageLegacy()) {
				openDirLauncher.launch(null);
			} else {
				openPicker();
			}
			return true;
		});
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			findPreference(PREF_ADD_CUTOUT_AREA).setVisible(true);
		}
		Preference backup = findPreference("pref_backup");
		Preference restore = findPreference("pref_restore");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			backup.setOnPreferenceClickListener(preference -> {
				showBackupScopeDialog();
				return true;
			});
			restore.setOnPreferenceClickListener(preference -> {
				restoreLauncher.launch(null);
				return true;
			});
		} else {
			// the system file picker used for backups exists since Android 4.4
			backup.setVisible(false);
			restore.setVisible(false);
		}
	}

	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	private void showBackupScopeDialog() {
		final int[] choice = {0};
		new AlertDialog.Builder(requireActivity())
				.setTitle(R.string.backup_choose_title)
				.setSingleChoiceItems(new CharSequence[]{
						getString(R.string.backup_scope_saves),
						getString(R.string.backup_scope_all)}, 0, (d, which) -> choice[0] = which)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					pendingBackupScope = choice[0] == 1 ? BackupScope.ALL : BackupScope.SAVES;
					backupLauncher.launch(BackupUi.suggestedName(pendingBackupScope));
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void onBackupTarget(Uri uri) {
		if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			BackupUi.startBackup(requireActivity(), uri, pendingBackupScope);
		}
	}

	private void onRestoreSource(Uri uri) {
		if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			BackupUi.startRestore(requireActivity(), uri);
		}
	}

	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
	private void openPicker() {
		try {
			startActivity(getFileManagerIntentOnDocumentProvider(Intent.ACTION_VIEW));
			return;
		} catch (ActivityNotFoundException ignored) {}

		try {
			startActivity(getFileManagerIntentOnDocumentProvider("android.provider.action.BROWSE"));
			return;
		} catch (ActivityNotFoundException ignored) {}

		try {
			// Just try to open the file manager, try the package name used on "normal" phones
			startActivity(getFileManagerIntent("com.google.android.documentsui"));
			return;
		} catch (ActivityNotFoundException ignored) {}

		try {
			// Next, try the AOSP package name
			startActivity(getFileManagerIntent("com.android.documentsui"));
		} catch (ActivityNotFoundException ignored) {}
	}

	private Intent getFileManagerIntent(String packageName) {
		// Fragile, but some phones don't expose the system file manager in any better way
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.setClassName(packageName, "com.android.documentsui.files.FilesActivity");
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return intent;
	}

	@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
	private Intent getFileManagerIntentOnDocumentProvider(String action) {
		String authority = requireContext().getPackageName() + ".documentProvider";
		String root = new File(Config.getEmulatorDir()).getAbsolutePath();
		Intent intent = new Intent(action);
		intent.addCategory(Intent.CATEGORY_DEFAULT);
		intent.setData(DocumentsContract.buildRootUri(authority, root));
		intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
				| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		return intent;
	}

	private void onPickDirResult(Uri uri) {
		if (uri == null) {
			return;
		}
		File file = Utils.getFileForUri(uri);
		String path = file.getAbsolutePath();
		if (!FileUtils.initWorkDir(file)) {
			new AlertDialog.Builder(requireActivity())
					.setTitle(R.string.error)
					.setCancelable(false)
					.setMessage(getString(R.string.create_apps_dir_failed, path))
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(R.string.choose, (d, w) -> openDirLauncher.launch(null))
					.show();
			return;
		}
		getPreferenceManager().getSharedPreferences().edit()
				.putString(PREF_EMULATOR_DIR, path)
				.apply();
		prefFolder.setSummary(path);
	}
}
