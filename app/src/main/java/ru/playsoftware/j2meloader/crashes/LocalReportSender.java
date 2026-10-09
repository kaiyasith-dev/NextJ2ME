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

package ru.playsoftware.j2meloader.crashes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.acra.ReportField;
import org.acra.data.CrashReportData;
import org.acra.sender.ReportSender;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.Constants;

/**
 * Keeps a crash report on the device instead of sending it anywhere: the report is written to
 * {@code crash.txt} in the emulator folder, for the user to attach to a bug report if they want to.
 * Nothing leaves the phone.
 */
public class LocalReportSender implements ReportSender {
	private static final String TAG = LocalReportSender.class.getName();
	static final String FILE_NAME = "crash.txt";

	@Override
	public void send(@NonNull Context context, @NonNull CrashReportData report) {
		File file = new File(Config.getEmulatorDir(), FILE_NAME);
		String text = format(header(report), report.getString(ReportField.STACK_TRACE),
				appInfo(report), report.getString(ReportField.LOGCAT));
		boolean saved = false;
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(text.getBytes("UTF-8"));
			saved = true;
		} catch (IOException e) {
			Log.e(TAG, "Can not save the crash report", e);
		}
		final String message = saved
				? "Crash report saved to:\n" + file.getPath()
				: "Can not save the crash report";
		new Handler(Looper.getMainLooper()).post(
				() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
	}

	private static String header(CrashReportData report) {
		return "App: " + report.getString(ReportField.APP_VERSION_NAME)
				+ " (" + report.getString(ReportField.APP_VERSION_CODE) + ")\n"
				+ "Device: " + report.getString(ReportField.BRAND)
				+ " " + report.getString(ReportField.PHONE_MODEL)
				+ ", Android " + report.getString(ReportField.ANDROID_VERSION) + "\n"
				+ "Time: " + report.getString(ReportField.USER_CRASH_DATE);
	}

	@Nullable
	private static String appInfo(CrashReportData report) {
		try {
			Object custom = report.get(ReportField.CUSTOM_DATA.name());
			if (custom instanceof JSONObject) {
				Object info = ((JSONObject) custom).opt(Constants.KEY_REPORT_APP_INFO);
				return info == null ? null : info.toString();
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "No app info in the report", e);
		}
		return null;
	}

	/** Puts the parts of a report into one text; parts that are missing are left out. */
	static String format(String header, String stackTrace, String appInfo, String logcat) {
		StringBuilder sb = new StringBuilder();
		append(sb, null, header);
		append(sb, "Error", stackTrace);
		append(sb, "Game", appInfo);
		append(sb, "Log", logcat);
		return sb.toString();
	}

	private static void append(StringBuilder sb, String title, String body) {
		if (body == null || body.isEmpty()) {
			return;
		}
		if (sb.length() > 0) {
			sb.append("\n\n");
		}
		if (title != null) {
			sb.append("==================== ").append(title).append(" ====================\n");
		}
		sb.append(body);
	}
}
