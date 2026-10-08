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

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.MigrationUtils;

/**
 * Dialogs and background work for Settings → Backup and restore. The data is written to, and
 * read from, one zip file chosen with the system file picker (Storage Access Framework), so no
 * storage permission is needed and the file can live anywhere, including removable storage.
 */
@RequiresApi(api = Build.VERSION_CODES.KITKAT)
public final class BackupUi {
	private static final String TAG = BackupUi.class.getName();
	private static final long UPDATE_INTERVAL_MS = 300;

	private BackupUi() {
	}

	public static String suggestedName(BackupScope scope) {
		String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
		return "NextJ2ME-" + (scope == BackupScope.ALL ? "full" : "saves") + "-" + stamp + ".zip";
	}

	/** Writes a backup of the work folder to the document {@code uri}. */
	public static void startBackup(Activity activity, Uri uri, BackupScope scope) {
		Context app = activity.getApplicationContext();
		ContentResolver resolver = activity.getContentResolver();
		BackupProgress progress = new BackupProgress();
		Progress ui = new Progress(activity, R.string.backup_running, progress);
		BackupInfo info = new BackupInfo(MigrationUtils.currentDataVersion(), scope,
				System.currentTimeMillis(), BuildConfig.VERSION_NAME);
		File root = new File(Config.getEmulatorDir());
		new Thread(() -> {
			Throwable error = null;
			try (OutputStream out = resolver.openOutputStream(uri, "w")) {
				if (out == null) {
					throw new IOException("Can't open the file for writing");
				}
				BackupWriter.write(root, out, scope, info, progress);
			} catch (Throwable t) {
				error = t;
			}
			final Throwable failure = error;
			if (failure != null) {
				deleteQuietly(resolver, uri);
			}
			ui.finish(() -> {
				if (failure instanceof BackupProgress.Cancelled) {
					Toast.makeText(app, R.string.backup_cancelled, Toast.LENGTH_SHORT).show();
				} else if (failure != null) {
					Log.e(TAG, "Backup failed", failure);
					showMessage(activity, R.string.backup_failed_title,
							app.getString(R.string.backup_failed, describe(failure)));
				} else {
					showMessage(activity, R.string.backup_done_title, app.getString(R.string.backup_done,
							progress.files(), Formatter.formatShortFileSize(app, progress.bytes())));
				}
			});
		}, "BackupWriter").start();
	}

	/** Checks the file, asks for confirmation, then restores it into the work folder. */
	public static void startRestore(Activity activity, Uri uri) {
		Context app = activity.getApplicationContext();
		ContentResolver resolver = activity.getContentResolver();
		Handler main = new Handler(Looper.getMainLooper());
		new Thread(() -> {
			BackupInfo info = null;
			Throwable error = null;
			try (InputStream in = resolver.openInputStream(uri)) {
				if (in == null) {
					throw new IOException("Can't open the file");
				}
				info = BackupReader.readInfo(in);
			} catch (Throwable t) {
				error = t;
			}
			final BackupInfo header = info;
			final Throwable failure = error;
			main.post(() -> {
				if (!alive(activity)) {
					return;
				}
				if (failure != null) {
					Log.e(TAG, "Not a usable backup", failure);
					showMessage(activity, R.string.restore_failed_title,
							app.getString(R.string.restore_failed, describe(failure)));
				} else {
					confirmRestore(activity, uri, header);
				}
			});
		}, "BackupCheck").start();
	}

	private static void confirmRestore(Activity activity, Uri uri, BackupInfo info) {
		Context app = activity.getApplicationContext();
		String date = info.created() > 0
				? DateFormat.getMediumDateFormat(app).format(new Date(info.created())) + " "
				+ DateFormat.getTimeFormat(app).format(new Date(info.created()))
				: app.getString(R.string.restore_unknown_date);
		String what = app.getString(info.scope() == BackupScope.ALL
				? R.string.backup_scope_all_short : R.string.backup_scope_saves_short);
		new AlertDialog.Builder(activity)
				.setTitle(R.string.restore_confirm_title)
				.setMessage(app.getString(R.string.restore_confirm_message, displayName(app, uri), date, what))
				.setPositiveButton(R.string.restore_action, (d, w) -> runRestore(activity, uri))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static void runRestore(Activity activity, Uri uri) {
		Context app = activity.getApplicationContext();
		ContentResolver resolver = activity.getContentResolver();
		BackupProgress progress = new BackupProgress();
		Progress ui = new Progress(activity, R.string.restore_running, progress);
		File root = new File(Config.getEmulatorDir());
		new Thread(() -> {
			BackupReader.Result result = null;
			Throwable error = null;
			try (InputStream in = resolver.openInputStream(uri)) {
				if (in == null) {
					throw new IOException("Can't open the file");
				}
				result = BackupReader.restore(in, root, MigrationUtils.currentDataVersion(), progress);
			} catch (Throwable t) {
				error = t;
			}
			final BackupReader.Result done = result;
			final Throwable failure = error;
			ui.finish(() -> {
				// whatever was restored before an error or cancel is in place, so refresh the list
				AppRepository.resyncInstalledApps();
				if (failure instanceof BackupProgress.Cancelled) {
					Toast.makeText(app, R.string.restore_cancelled, Toast.LENGTH_LONG).show();
				} else if (failure != null) {
					Log.e(TAG, "Restore failed", failure);
					showMessage(activity, R.string.restore_failed_title,
							app.getString(R.string.restore_failed, describe(failure)));
				} else {
					String message = app.getString(R.string.restore_done, done.files);
					if (done.skipped > 0) {
						message += "\n" + app.getString(R.string.restore_skipped, done.skipped);
					}
					showMessage(activity, R.string.restore_done_title, message);
				}
			});
		}, "BackupReader").start();
	}

	private static boolean alive(Activity activity) {
		return !activity.isFinishing() && !activity.isDestroyed();
	}

	private static String describe(Throwable t) {
		return t.getMessage() != null ? t.getMessage() : t.toString();
	}

	private static void deleteQuietly(ContentResolver resolver, Uri uri) {
		try {
			DocumentsContract.deleteDocument(resolver, uri);
		} catch (Exception e) {
			Log.w(TAG, "Can't remove the incomplete backup file", e);
		}
	}

	private static String displayName(Context context, Uri uri) {
		try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
				null, null, null)) {
			if (c != null && c.moveToFirst() && !c.isNull(0)) {
				return c.getString(0);
			}
		} catch (Exception ignored) {
			// fall back to the last segment of the uri
		}
		String last = uri.getLastPathSegment();
		return last != null ? last : uri.toString();
	}

	private static void showMessage(Activity activity, int titleRes, String message) {
		if (!alive(activity)) {
			Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_LONG).show();
			return;
		}
		new AlertDialog.Builder(activity)
				.setTitle(titleRes)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	/** A cancellable dialog that shows how many files are done while the worker runs. */
	private static final class Progress {
		private final Activity activity;
		private final Handler main = new Handler(Looper.getMainLooper());
		private final BackupProgress progress;
		@Nullable private AlertDialog dialog;
		private volatile boolean finished;

		Progress(Activity activity, int titleRes, BackupProgress progress) {
			this.activity = activity;
			this.progress = progress;
			dialog = new AlertDialog.Builder(activity)
					.setTitle(titleRes)
					.setMessage(text())
					.setCancelable(false)
					.setNegativeButton(android.R.string.cancel, null)
					.create();
			dialog.show();
			// keep the dialog open while the worker stops, and show that we are stopping
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
				progress.cancel();
				v.setEnabled(false);
			});
			tick();
		}

		private String text() {
			Context app = activity.getApplicationContext();
			return app.getString(R.string.backup_progress, progress.files(),
					Formatter.formatShortFileSize(app, progress.bytes()));
		}

		private void tick() {
			if (finished || dialog == null) {
				return;
			}
			dialog.setMessage(text());
			main.postDelayed(this::tick, UPDATE_INTERVAL_MS);
		}

		/** Called on the worker thread: closes the dialog, then runs {@code after} on the UI thread. */
		void finish(Runnable after) {
			finished = true;
			main.post(() -> {
				try {
					if (dialog != null) {
						dialog.dismiss();
					}
				} catch (RuntimeException ignored) {
					// the activity was destroyed while the worker ran
				}
				dialog = null;
				after.run();
			});
		}
	}
}
