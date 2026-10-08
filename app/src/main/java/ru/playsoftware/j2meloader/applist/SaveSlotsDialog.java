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

package ru.playsoftware.j2meloader.applist;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.ProcessUtils;
import ru.playsoftware.j2meloader.util.SaveSlots;
import ru.playsoftware.j2meloader.util.StorageSize;

/**
 * The "Save slots" screen of a game: shows its slots, which one the game uses, and lets the user
 * make, copy, rename, delete and choose them. The game reads the chosen slot when it starts, so a
 * change takes effect the next time the game is opened; nothing can be changed while it runs.
 */
final class SaveSlotsDialog {
	private final Activity activity;
	private final AppItem item;
	private final Runnable onChanged;
	private final SaveSlots slots = AppUtils.saveSlots();
	private final String game;

	private SaveSlotsDialog(Activity activity, AppItem item, Runnable onChanged) {
		this.activity = activity;
		this.item = item;
		this.onChanged = onChanged;
		this.game = item.getPath();
	}

	/** @param onChanged called after something changed (the games list refreshes its sizes) */
	static void show(Activity activity, AppItem item, Runnable onChanged) {
		new SaveSlotsDialog(activity, item, onChanged).showList();
	}

	private String defaultLabel() {
		return activity.getString(R.string.save_slots_default);
	}

	private void showList() {
		final List<String> names = new ArrayList<>();
		names.add(SaveSlots.DEFAULT);
		names.addAll(slots.list(game));
		String active = slots.active(game);
		String[] labels = new String[names.size()];
		for (int i = 0; i < labels.length; i++) {
			String slot = names.get(i);
			String mark = slot.equals(active) ? "●  " : "      ";
			labels[i] = mark + SaveSlots.label(slot, defaultLabel()) + "  ·  "
					+ activity.getString(R.string.size_kb,
					StorageSize.formatKb(slots.size(game, slot), Locale.getDefault()));
		}
		new AlertDialog.Builder(activity)
				.setTitle(activity.getString(R.string.save_slots_title, item.getTitle()))
				.setItems(labels, (d, which) -> showActions(names.get(which)))
				.setPositiveButton(R.string.save_slots_new, (d, w) -> askNewSlot())
				.setNegativeButton(R.string.close, null)
				.show();
	}

	private void showActions(final String slot) {
		boolean isDefault = slot.isEmpty();
		boolean isActive = slot.equals(slots.active(game));
		final List<Runnable> actions = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		if (!isActive) {
			labels.add(activity.getString(R.string.save_slots_use));
			actions.add(() -> use(slot));
		}
		labels.add(activity.getString(R.string.save_slots_duplicate));
		actions.add(() -> askName(R.string.save_slots_duplicate_title, "", name -> duplicate(slot, name)));
		if (!isDefault) {
			labels.add(activity.getString(R.string.save_slots_rename));
			actions.add(() -> askName(R.string.save_slots_rename_title, slot, name -> rename(slot, name)));
			labels.add(activity.getString(R.string.save_slots_delete));
			actions.add(() -> confirmDelete(slot));
		}
		new AlertDialog.Builder(activity)
				.setTitle(SaveSlots.label(slot, defaultLabel()))
				.setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
				.setNegativeButton(android.R.string.cancel, (d, w) -> showList())
				.setOnCancelListener(d -> showList())
				.show();
	}

	// ------------------------------------------------------------------ actions

	/** Nothing may change while the game has the saves open. */
	private boolean blocked() {
		if (ProcessUtils.isMidletRunning(activity)) {
			Toast.makeText(activity, R.string.save_slots_game_running, Toast.LENGTH_LONG).show();
			showList();
			return true;
		}
		return false;
	}

	private interface Op {
		void run() throws IOException;
	}

	private void perform(Op op, String doneMessage) {
		if (blocked()) {
			return;
		}
		try {
			op.run();
			if (doneMessage != null) {
				Toast.makeText(activity, doneMessage, Toast.LENGTH_SHORT).show();
			}
		} catch (IOException | IllegalArgumentException e) {
			Toast.makeText(activity, activity.getString(R.string.save_slots_failed, e.getMessage()),
					Toast.LENGTH_LONG).show();
		}
		onChanged.run();
		showList();
	}

	private void use(final String slot) {
		perform(() -> slots.setActive(game, slot), activity.getString(R.string.save_slots_now_using,
				SaveSlots.label(slot, defaultLabel())));
	}

	private void duplicate(final String from, final String name) {
		perform(() -> slots.duplicate(game, from, name), null);
	}

	private void rename(final String from, final String name) {
		perform(() -> slots.rename(game, from, name), null);
	}

	private void confirmDelete(final String slot) {
		new AlertDialog.Builder(activity)
				.setTitle(R.string.save_slots_delete)
				.setMessage(activity.getString(R.string.save_slots_delete_confirm, slot))
				.setPositiveButton(android.R.string.ok, (d, w) -> perform(() -> slots.delete(game, slot), null))
				.setNegativeButton(android.R.string.cancel, (d, w) -> showList())
				.setOnCancelListener(d -> showList())
				.show();
	}

	/** Asks for a name, then whether the new slot starts empty or as a copy of the one in use. */
	private void askNewSlot() {
		askName(R.string.save_slots_new_title, "", name -> {
			final String active = slots.active(game);
			String copyLabel = activity.getString(R.string.save_slots_new_copy,
					SaveSlots.label(active, defaultLabel()));
			new AlertDialog.Builder(activity)
					.setTitle(name)
					.setItems(new String[]{activity.getString(R.string.save_slots_new_empty), copyLabel},
							(d, which) -> {
								if (which == 0) {
									perform(() -> slots.create(game, name), null);
								} else {
									perform(() -> slots.duplicate(game, active, name), null);
								}
							})
					.setNegativeButton(android.R.string.cancel, (d, w) -> showList())
					.setOnCancelListener(d -> showList())
					.show();
		});
	}

	private interface NameListener {
		void onName(String name);
	}

	private void askName(int titleRes, String initial, final NameListener listener) {
		final EditText edit = new EditText(activity);
		edit.setSingleLine();
		edit.setHint(R.string.save_slots_name_hint);
		edit.setText(initial);
		edit.setSelection(edit.getText().length());
		float density = activity.getResources().getDisplayMetrics().density;
		LinearLayout box = new LinearLayout(activity);
		int margin = (int) (density * 20);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.setMargins(margin, 0, margin, 0);
		box.addView(edit, params);
		new AlertDialog.Builder(activity)
				.setTitle(titleRes)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					try {
						// reject an unusable name here, so the message is shown right away
						SaveSlots.normalize(edit.getText().toString());
					} catch (IllegalArgumentException e) {
						Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
						showList();
						return;
					}
					listener.onName(edit.getText().toString());
				})
				.setNegativeButton(android.R.string.cancel, (d, w) -> showList())
				.setOnCancelListener(d -> showList())
				.show();
	}
}
