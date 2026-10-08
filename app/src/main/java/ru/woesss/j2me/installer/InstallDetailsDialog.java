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

package ru.woesss.j2me.installer;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.databinding.DialogInstallDetailsBinding;
import ru.woesss.j2me.jar.Descriptor;

/** The pop-up shown before a new game is installed, so its name, vendor, version... can be changed. */
final class InstallDetailsDialog {
	/** Attributes that have their own field, so they are not repeated in the advanced list. */
	private static final Set<String> DEDICATED = new HashSet<>(Arrays.asList(
			Descriptor.MIDLET_NAME, Descriptor.MIDLET_VENDOR, Descriptor.MIDLET_VERSION,
			Descriptor.MIDLET_DESCRIPTION));

	interface Listener {
		void onConfirmed(InstallEdits edits);

		void onCancelled();
	}

	private InstallDetailsDialog() {
	}

	static AlertDialog show(Activity activity, Descriptor descriptor, boolean downloadsJar, Listener listener) {
		DialogInstallDetailsBinding b = DialogInstallDetailsBinding.inflate(LayoutInflater.from(activity));
		Map<String, String> shown = new HashMap<>(descriptor.getAttrs());
		b.detailsName.setText(shown.get(Descriptor.MIDLET_NAME));
		b.detailsVendor.setText(shown.get(Descriptor.MIDLET_VENDOR));
		b.detailsVersion.setText(shown.get(Descriptor.MIDLET_VERSION));
		b.detailsDescription.setText(shown.get(Descriptor.MIDLET_DESCRIPTION));
		b.detailsAttributes.setText(InstallEdits.format(shown, DEDICATED));
		b.detailsNote.setVisibility(downloadsJar ? View.VISIBLE : View.GONE);
		b.detailsAdvancedToggle.setOnClickListener(v -> {
			boolean open = b.detailsAdvanced.getVisibility() != View.VISIBLE;
			b.detailsAdvanced.setVisibility(open ? View.VISIBLE : View.GONE);
			b.detailsAdvancedToggle.setText(open
					? R.string.install_details_hide_advanced : R.string.install_details_show_advanced);
		});

		AlertDialog dialog = new AlertDialog.Builder(activity)
				.setIcon(R.mipmap.ic_launcher)
				.setTitle(R.string.install_details_title)
				.setView(b.getRoot())
				.setCancelable(false)
				.setPositiveButton(R.string.install, null)
				.setNegativeButton(android.R.string.cancel, (d, w) -> listener.onCancelled())
				.create();
		// set here so that a validation error keeps the dialog open
		dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			InstallEdits edits = validate(activity, b, shown);
			if (edits != null) {
				dialog.dismiss();
				listener.onConfirmed(edits);
			}
		}));
		dialog.show();
		return dialog;
	}

	/** Returns the changes, or null after marking the field that is wrong. */
	private static InstallEdits validate(Activity activity, DialogInstallDetailsBinding b,
										 Map<String, String> shown) {
		String name = text(b.detailsName);
		String vendor = text(b.detailsVendor);
		String version = text(b.detailsVersion);
		if (name.isEmpty()) {
			b.detailsName.setError(activity.getString(R.string.install_details_required));
			b.detailsName.requestFocus();
			return null;
		}
		if (vendor.isEmpty()) {
			b.detailsVendor.setError(activity.getString(R.string.install_details_required));
			b.detailsVendor.requestFocus();
			return null;
		}
		if (version.isEmpty()) {
			b.detailsVersion.setError(activity.getString(R.string.install_details_required));
			b.detailsVersion.requestFocus();
			return null;
		}
		Map<String, String> edited;
		try {
			edited = InstallEdits.parse(b.detailsAttributes.getText().toString());
		} catch (IllegalArgumentException e) {
			showAdvancedError(activity, b, activity.getString(R.string.install_details_bad_line, e.getMessage()));
			return null;
		}
		for (String key : DEDICATED) {
			if (edited.containsKey(key)) {
				showAdvancedError(activity, b, activity.getString(R.string.install_details_use_field, key));
				return null;
			}
		}
		edited.put(Descriptor.MIDLET_NAME, name);
		edited.put(Descriptor.MIDLET_VENDOR, vendor);
		edited.put(Descriptor.MIDLET_VERSION, version);
		String description = text(b.detailsDescription);
		if (!description.isEmpty()) {
			edited.put(Descriptor.MIDLET_DESCRIPTION, description);
		}
		return InstallEdits.diff(shown, edited);
	}

	private static void showAdvancedError(Activity activity, DialogInstallDetailsBinding b, String message) {
		b.detailsAdvanced.setVisibility(View.VISIBLE);
		b.detailsAdvancedToggle.setText(R.string.install_details_hide_advanced);
		b.detailsAttributes.setError(message);
		b.detailsAttributes.requestFocus();
	}

	private static String text(EditText e) {
		return e.getText().toString().trim();
	}
}
