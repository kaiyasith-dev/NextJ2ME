/*
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

package ru.playsoftware.j2meloader.debugger.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.databinding.DialogMemoryDebuggerBinding;
import ru.playsoftware.j2meloader.debugger.AddressSpace;
import ru.playsoftware.j2meloader.debugger.MemoryCheat;
import ru.playsoftware.j2meloader.debugger.MemoryDebugger;
import ru.playsoftware.j2meloader.debugger.MemoryFreeze;
import ru.playsoftware.j2meloader.debugger.MemoryLocation;
import ru.playsoftware.j2meloader.debugger.MemoryReference;
import ru.playsoftware.j2meloader.debugger.MemoryTarget;
import ru.playsoftware.j2meloader.debugger.MemoryValue;
import ru.playsoftware.j2meloader.debugger.MemoryWatch;
import ru.playsoftware.j2meloader.debugger.ScanMode;
import ru.playsoftware.j2meloader.debugger.ScanParams;
import ru.playsoftware.j2meloader.debugger.ScanScope;
import ru.playsoftware.j2meloader.debugger.ScanSession;
import ru.playsoftware.j2meloader.debugger.StringEncoding;
import ru.playsoftware.j2meloader.debugger.UnavailableException;
import ru.playsoftware.j2meloader.debugger.ValueType;

/**
 * Full screen Memory Debugger. It is a dialog fragment of the game's activity, so opening it does
 * not pause or restart the MIDlet; the game only stops if the user presses Pause (or while a scan
 * with "pause while scanning" runs). Closing it always lets the game run again.
 */
public class MemoryDebuggerDialog extends DialogFragment implements MemoryDebugger.Listener {
	private static final String TAG = "MemoryDebuggerDialog";

	private static final int TAB_SCAN = 0;
	private static final int TAB_MEMORY = 1;
	private static final int TAB_WATCH = 2;
	private static final int TAB_FROZEN = 3;
	private static final int TAB_CHEATS = 4;

	private static final int RESULT_PAGE = 50;
	private static final int ROW_BYTES = 8;
	private static final int ROWS = 32;
	private static final int PAGE_BYTES = ROW_BYTES * ROWS;
	private static final long REFRESH_MS = 500;
	private static final int SELECTED_BG = 0x44808080;

	private static final ScanScope[] SCOPES = ScanScope.values();
	private static final ValueType[] TYPES = ValueType.values();
	private static final ScanMode[] MODES = ScanMode.values();
	private static final StringEncoding[] ENCODINGS = StringEncoding.values();
	private static final int[] ALIGNMENTS = {0, 1, 2, 4, 8};

	/** Opens the debugger over the running game. */
	public static void show(AppCompatActivity activity) {
		if (activity.getSupportFragmentManager().findFragmentByTag(TAG) == null) {
			new MemoryDebuggerDialog().show(activity.getSupportFragmentManager(), TAG);
		}
	}

	private interface Pick {
		void pick(int position);
	}

	private interface TextCallback {
		void done(String text);
	}

	private interface RefCallback {
		void done(MemoryReference ref);
	}

	private static final class ResultRow {
		final MemoryLocation location;
		final MemoryValue previous;
		final View view;
		final TextView value;

		ResultRow(MemoryLocation location, MemoryValue previous, View view, TextView value) {
			this.location = location;
			this.previous = previous;
			this.view = view;
			this.value = value;
		}
	}

	private static final class TargetRow {
		final MemoryTarget target;
		final TextView value;

		TargetRow(MemoryTarget target, TextView value) {
			this.target = target;
			this.value = value;
		}
	}

	private DialogMemoryDebuggerBinding b;
	private MemoryDebugger dbg;
	private final Handler main = new Handler(Looper.getMainLooper());
	private boolean updatingUi;
	private int tab = TAB_SCAN;
	private int shownGeneration = -1;

	// scan pane
	private int resultLimit = RESULT_PAGE;
	private ScanSession shownSession;
	private long shownCount = -1;
	private final List<ResultRow> resultRows = new ArrayList<>();

	// selection
	private MemoryLocation selected;

	// memory pane
	private MemoryDebugger.ViewTarget view;
	private int pageStart;
	private int selOffset = -1;
	private TextView[] addrViews;
	private TextView[] byteViews;
	private TextView[] asciiViews;

	// lists
	private final List<TargetRow> watchRows = new ArrayList<>();
	private final List<TargetRow> frozenRows = new ArrayList<>();
	private final List<TargetRow> cheatRows = new ArrayList<>();

	private final Runnable ticker = new Runnable() {
		@Override
		public void run() {
			if (b == null) {
				return;
			}
			refreshVisible();
			main.postDelayed(this, REFRESH_MS);
		}
	};

	private final Runnable structural = new Runnable() {
		@Override
		public void run() {
			if (b != null && dbg != null) {
				updateHeader();
				refreshSessions();
				syncResultsWithSession();
				renderTargets();
			}
		}
	};

	// ================================================================== lifecycle

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Dialog d = new Dialog(requireContext(), R.style.AppTheme_DebuggerDialog);
		Window w = d.getWindow();
		if (w != null) {
			w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
					| WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
		}
		return d;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		b = DialogMemoryDebuggerBinding.inflate(inflater, container, false);
		return b.getRoot();
	}

	@Override
	public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(v, savedInstanceState);
		dbg = MemoryDebugger.get();
		if (dbg == null || dbg.isDestroyed()) {
			Toast.makeText(requireContext(), R.string.memdbg_not_running, Toast.LENGTH_LONG).show();
			dismissAllowingStateLoss();
			return;
		}
		setupHeader();
		setupScanPane();
		setupMemoryPane();
		setupSelectedPanel();
		setupFrozenPane();
		showTab(TAB_SCAN);
	}

	@Override
	public void onStart() {
		super.onStart();
		Dialog d = getDialog();
		if (d != null && d.getWindow() != null) {
			d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		}
		if (dbg != null && b != null) {
			dbg.addListener(this);
			structural.run();
			main.removeCallbacks(ticker);
			main.postDelayed(ticker, REFRESH_MS);
		}
	}

	@Override
	public void onStop() {
		main.removeCallbacks(ticker);
		main.removeCallbacks(structural);
		if (dbg != null) {
			dbg.removeListener(this);
		}
		super.onStop();
	}

	@Override
	public void onDestroyView() {
		b = null;
		resultRows.clear();
		super.onDestroyView();
	}

	@Override
	public void onDismiss(@NonNull DialogInterface dialog) {
		super.onDismiss(dialog);
		// closing the debugger never leaves the game frozen
		if (dbg != null) {
			dbg.resumeGame();
		}
	}

	/** Debugger notification, any thread. */
	@Override
	public void onChanged() {
		main.removeCallbacks(structural);
		main.post(structural);
	}

	// ================================================================== helpers

	private void toast(String text) {
		if (isAdded()) {
			Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
		}
	}

	private void toast(@StringRes int res) {
		if (isAdded()) {
			Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show();
		}
	}

	private int dp(float v) {
		return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
	}

	private static AdapterView.OnItemSelectedListener picker(final Pick cb) {
		return new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				cb.pick(position);
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		};
	}

	private void bind(Spinner s, List<String> labels, int selection, Pick onPick) {
		ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_spinner_item, labels);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		s.setOnItemSelectedListener(null);
		s.setAdapter(adapter);
		s.setSelection(Math.max(0, Math.min(selection, labels.size() - 1)), false);
		if (onPick != null) {
			s.setOnItemSelectedListener(picker(onPick));
		}
	}

	private void prompt(@StringRes int title, String initial, final TextCallback cb) {
		final EditText input = new EditText(requireContext());
		input.setSingleLine(true);
		input.setText(initial);
		input.setSelection(input.getText().length());
		new AlertDialog.Builder(requireContext())
				.setTitle(title)
				.setView(padded(input))
				.setPositiveButton(android.R.string.ok, (d, w) -> cb.done(input.getText().toString()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private View padded(View v) {
		LinearLayout box = new LinearLayout(requireContext());
		box.setPadding(dp(20), dp(8), dp(20), 0);
		box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT));
		return box;
	}

	private static boolean isOpaque(ValueType t) {
		return t == ValueType.BYTES || t == ValueType.STRING;
	}

	private static String fmt(MemoryValue v) {
		return v == null ? "?" : v.format();
	}

	// ================================================================== header and tabs

	private void setupHeader() {
		b.btnClose.setOnClickListener(v -> dismiss());
		b.btnPause.setOnClickListener(v -> {
			dbg.pauseGame();
			updateHeader();
		});
		b.btnResume.setOnClickListener(v -> {
			dbg.resumeGame();
			updateHeader();
		});
		b.tabScan.setOnClickListener(v -> showTab(TAB_SCAN));
		b.tabMemory.setOnClickListener(v -> showTab(TAB_MEMORY));
		b.tabWatch.setOnClickListener(v -> showTab(TAB_WATCH));
		b.tabFrozen.setOnClickListener(v -> showTab(TAB_FROZEN));
		b.tabCheats.setOnClickListener(v -> showTab(TAB_CHEATS));
	}

	private void showTab(int t) {
		tab = t;
		b.paneScan.setVisibility(t == TAB_SCAN ? View.VISIBLE : View.GONE);
		b.paneMemory.setVisibility(t == TAB_MEMORY ? View.VISIBLE : View.GONE);
		b.paneWatch.setVisibility(t == TAB_WATCH ? View.VISIBLE : View.GONE);
		b.paneFrozen.setVisibility(t == TAB_FROZEN ? View.VISIBLE : View.GONE);
		b.paneCheats.setVisibility(t == TAB_CHEATS ? View.VISIBLE : View.GONE);
		b.panelSelected.setVisibility(t == TAB_SCAN || t == TAB_MEMORY ? View.VISIBLE : View.GONE);
		View[] tabs = {b.tabScan, b.tabMemory, b.tabWatch, b.tabFrozen, b.tabCheats};
		for (int i = 0; i < tabs.length; i++) {
			tabs[i].setAlpha(i == t ? 1f : 0.55f);
		}
		renderTargets();
		refreshVisible();
	}

	private void updateHeader() {
		if (b == null || dbg == null) {
			return;
		}
		boolean paused = dbg.isGamePaused();
		String run = paused ? getString(R.string.memdbg_state_parked, dbg.parkedThreads())
				: getString(R.string.memdbg_state_running);
		b.stateText.setText(dbg.statsText() + " · " + run);
		b.btnPause.setEnabled(!paused);
		b.btnResume.setEnabled(paused);
		String note = dbg.invalidationNote();
		if (note != null && shownGeneration != dbg.generation()) {
			shownGeneration = dbg.generation();
			toast(getString(R.string.memdbg_restarted, note));
		}
	}

	private void refreshVisible() {
		if (b == null || dbg == null) {
			return;
		}
		updateHeader();
		switch (tab) {
			case TAB_SCAN:
				refreshResultValues();
				break;
			case TAB_MEMORY:
				renderPage();
				break;
			default:
				refreshTargetValues();
		}
	}

	// ================================================================== scan pane

	private void setupScanPane() {
		ScanParams p = dbg.settings().scan;
		List<String> scopes = new ArrayList<>();
		for (ScanScope s : SCOPES) {
			scopes.add(s.label());
		}
		bind(b.spinScope, scopes, p.scope.ordinal(), null);
		List<String> types = new ArrayList<>();
		for (ValueType t : TYPES) {
			types.add(t.label());
		}
		bind(b.spinType, types, p.type.ordinal(), pos -> updateValueField());
		List<String> modes = new ArrayList<>();
		for (ScanMode m : MODES) {
			modes.add(m.label());
		}
		bind(b.spinMode, modes, p.mode.ordinal(), pos -> updateValueField());
		List<String> aligns = new ArrayList<>();
		for (int a : ALIGNMENTS) {
			aligns.add(a == 0 ? "Natural" : String.valueOf(a));
		}
		int alignIdx = 0;
		for (int i = 0; i < ALIGNMENTS.length; i++) {
			if (ALIGNMENTS[i] == p.alignment) {
				alignIdx = i;
			}
		}
		bind(b.spinAlignment, aligns, alignIdx, null);
		List<String> encs = new ArrayList<>();
		for (StringEncoding e : ENCODINGS) {
			encs.add(e.label());
		}
		bind(b.spinEncoding, encs, p.encoding.ordinal(), null);
		b.checkBigEndian.setChecked(p.bigEndian);
		b.checkPauseScan.setChecked(p.pauseDuringScan);
		b.editValue.setText(p.value);
		updateValueField();

		b.btnNewScan.setOnClickListener(v -> startScan(true));
		b.btnNextScan.setOnClickListener(v -> startScan(false));
		b.btnResetScan.setOnClickListener(v -> {
			dbg.resetScan();
			resultLimit = RESULT_PAGE;
			b.scanStatus.setText("");
		});
		b.btnCancelScan.setOnClickListener(v -> dbg.cancelScan());
		b.btnHistory.setOnClickListener(v -> showHistory());
		b.btnExport.setOnClickListener(v -> exportScan());
		b.btnImport.setOnClickListener(v -> importScan());
		b.btnMoreResults.setOnClickListener(v -> {
			resultLimit += RESULT_PAGE;
			renderResults();
		});
		b.editValue.setImeOptions(EditorInfo.IME_ACTION_DONE);
	}

	private void updateValueField() {
		if (b == null) {
			return;
		}
		ScanMode mode = MODES[b.spinMode.getSelectedItemPosition()];
		ValueType type = TYPES[b.spinType.getSelectedItemPosition()];
		b.editValue.setVisibility(mode.needsValue() ? View.VISIBLE : View.GONE);
		b.editValue.setHint(type == ValueType.BYTES ? R.string.memdbg_value_hint_bytes
				: type == ValueType.STRING ? R.string.memdbg_value_hint_text : R.string.memdbg_value);
	}

	private ScanParams readScanParams() {
		ScanParams p = new ScanParams();
		p.scope = SCOPES[b.spinScope.getSelectedItemPosition()];
		p.type = TYPES[b.spinType.getSelectedItemPosition()];
		p.mode = MODES[b.spinMode.getSelectedItemPosition()];
		p.value = b.editValue.getText().toString();
		p.bigEndian = b.checkBigEndian.isChecked();
		p.alignment = ALIGNMENTS[b.spinAlignment.getSelectedItemPosition()];
		p.encoding = ENCODINGS[b.spinEncoding.getSelectedItemPosition()];
		p.pauseDuringScan = b.checkPauseScan.isChecked();
		return p;
	}

	private void setScanning(boolean scanning) {
		b.scanProgressRow.setVisibility(scanning ? View.VISIBLE : View.GONE);
		b.btnNewScan.setEnabled(!scanning);
		b.btnNextScan.setEnabled(!scanning);
		b.btnResetScan.setEnabled(!scanning);
	}

	private void startScan(boolean first) {
		if (dbg.isScanning()) {
			return;
		}
		ScanParams p = readScanParams();
		setScanning(true);
		b.scanStatus.setText(getString(R.string.memdbg_scanning, 0L, 0L));
		MemoryDebugger.ScanListener listener = new MemoryDebugger.ScanListener() {
			@Override
			public void onProgress(long regionsDone, long candidates) {
				main.post(() -> {
					if (b != null && isAdded()) {
						b.scanStatus.setText(getString(R.string.memdbg_scanning, regionsDone, candidates));
					}
				});
			}

			@Override
			public void onFinished(ScanSession session) {
				main.post(() -> {
					if (b == null || !isAdded()) {
						return;
					}
					setScanning(false);
					resultLimit = RESULT_PAGE;
					shownSession = null;
					syncControlsToSession(session);
					syncResultsWithSession();
					refreshSessions();
				});
			}

			@Override
			public void onCancelled() {
				main.post(() -> {
					if (b != null && isAdded()) {
						setScanning(false);
						b.scanStatus.setText(R.string.memdbg_scan_cancelled);
					}
				});
			}

			@Override
			public void onError(String message) {
				main.post(() -> {
					if (b != null && isAdded()) {
						setScanning(false);
						b.scanStatus.setText(message);
						toast(message);
					}
				});
			}
		};
		if (first) {
			dbg.startNewScan(p, listener);
		} else {
			dbg.startNextScan(p, listener);
		}
	}

	/** Shows the fixed settings of a session (scope, type, byte order, encoding) in the controls. */
	private void syncControlsToSession(ScanSession s) {
		if (s == null || b == null) {
			return;
		}
		updatingUi = true;
		b.spinScope.setSelection(s.scope.ordinal(), false);
		b.spinType.setSelection(s.type.ordinal(), false);
		b.spinEncoding.setSelection(s.encoding.ordinal(), false);
		b.checkBigEndian.setChecked(s.bigEndian);
		updatingUi = false;
		updateValueField();
	}

	private void refreshSessions() {
		if (b == null) {
			return;
		}
		List<ScanSession> sessions = dbg.sessions();
		ScanSession active = dbg.activeSession();
		List<String> labels = new ArrayList<>();
		int sel = 0;
		for (int i = 0; i < sessions.size(); i++) {
			ScanSession s = sessions.get(i);
			labels.add(getString(R.string.memdbg_session_label, s.id, s.type.label(), s.resultCount()));
			if (s == active) {
				sel = i;
			}
		}
		b.spinSession.setVisibility(sessions.isEmpty() ? View.GONE : View.VISIBLE);
		if (sessions.isEmpty()) {
			return;
		}
		final List<ScanSession> snapshot = sessions;
		bind(b.spinSession, labels, sel, pos -> {
			if (pos >= 0 && pos < snapshot.size() && snapshot.get(pos) != dbg.activeSession()) {
				dbg.setActiveSession(snapshot.get(pos));
				resultLimit = RESULT_PAGE;
				syncControlsToSession(snapshot.get(pos));
				syncResultsWithSession();
			}
		});
	}

	private void showHistory() {
		ScanSession s = dbg.activeSession();
		StringBuilder sb = new StringBuilder();
		if (s != null) {
			int i = 1;
			for (ScanSession.Step step : s.history()) {
				sb.append(i++).append(". ").append(step).append('\n');
			}
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.memdbg_history)
				.setMessage(sb.length() == 0 ? getString(R.string.memdbg_history_empty) : sb.toString().trim())
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private File scansDir() {
		File store = dbg.storeFile();
		return store == null ? null : new File(store.getParentFile(), "scans");
	}

	private String appId() {
		File store = dbg.storeFile();
		if (store == null) {
			return "game";
		}
		String n = store.getName();
		return n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
	}

	private void exportScan() {
		final ScanSession s = dbg.activeSession();
		File dir = scansDir();
		if (s == null || dir == null) {
			toast(R.string.memdbg_no_scan);
			return;
		}
		final File file = new File(dir, appId() + "-scan" + s.id + "-" + System.currentTimeMillis() + ".json");
		dbg.runAsync(() -> {
			try {
				int n = dbg.exportResults(s, file);
				main.post(() -> toast(getString(R.string.memdbg_exported, n, file.getName())));
			} catch (IOException | RuntimeException e) {
				main.post(() -> toast(String.valueOf(e.getMessage())));
			}
		});
	}

	private void importScan() {
		File dir = scansDir();
		File[] files = dir == null ? null : dir.listFiles();
		final List<File> mine = new ArrayList<>();
		if (files != null) {
			for (File f : files) {
				if (f.getName().startsWith(appId() + "-scan") && f.getName().endsWith(".json")) {
					mine.add(f);
				}
			}
		}
		if (mine.isEmpty()) {
			toast(R.string.memdbg_import_none);
			return;
		}
		Collections.sort(mine, new Comparator<File>() {
			@Override
			public int compare(File a, File c) {
				return Long.compare(c.lastModified(), a.lastModified());
			}
		});
		String[] names = new String[mine.size()];
		for (int i = 0; i < names.length; i++) {
			names[i] = mine.get(i).getName();
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.memdbg_import)
				.setItems(names, (d, which) -> {
					final File file = mine.get(which);
					dbg.runAsync(() -> {
						try {
							ScanSession s = dbg.importResults(file);
							main.post(() -> {
								resultLimit = RESULT_PAGE;
								shownSession = null;
								syncResultsWithSession();
								refreshSessions();
								toast(getString(R.string.memdbg_imported, (int) s.resultCount()));
							});
						} catch (IOException | RuntimeException e) {
							main.post(() -> toast(String.valueOf(e.getMessage())));
						}
					});
				})
				.show();
	}

	// ------------------------------------------------------------------ results

	/** Re-renders the result list when the active session or its size changed. */
	private void syncResultsWithSession() {
		if (b == null) {
			return;
		}
		ScanSession s = dbg.activeSession();
		long count = s == null ? -1 : s.resultCount();
		if (s != shownSession || count != shownCount) {
			renderResults();
		}
	}

	private void renderResults() {
		b.resultsContainer.removeAllViews();
		resultRows.clear();
		ScanSession s = dbg.activeSession();
		shownSession = s;
		shownCount = s == null ? -1 : s.resultCount();
		if (s == null) {
			b.scanStatus.setText(R.string.memdbg_no_scan);
			b.btnMoreResults.setVisibility(View.GONE);
			return;
		}
		b.scanStatus.setText(getString(s.isTruncated() ? R.string.memdbg_scan_truncated : R.string.memdbg_scan_done,
				(int) s.resultCount()));
		LayoutInflater inflater = LayoutInflater.from(requireContext());
		for (MemoryDebugger.ScanResult r : dbg.results(s, 0, resultLimit)) {
			View row = inflater.inflate(R.layout.list_row_debug, b.resultsContainer, false);
			TextView title = row.findViewById(R.id.row_title);
			TextView subtitle = row.findViewById(R.id.row_subtitle);
			TextView value = row.findViewById(R.id.row_value);
			title.setText(dbg.describe(r.location));
			subtitle.setText(dbg.describeDetail(r.location));
			final MemoryLocation loc = r.location;
			row.setOnClickListener(v -> select(loc));
			b.resultsContainer.addView(row);
			resultRows.add(new ResultRow(loc, r.previous, row, value));
		}
		b.btnMoreResults.setVisibility(s.resultCount() > resultLimit ? View.VISIBLE : View.GONE);
		refreshResultValues();
		highlightSelection();
	}

	private void refreshResultValues() {
		for (ResultRow r : resultRows) {
			MemoryValue cur = dbg.read(r.location);
			if (cur == null) {
				r.value.setText(R.string.memdbg_unavailable);
			} else if (r.previous != null && !r.previous.equals(cur)) {
				r.value.setText(cur.format() + "  (was " + r.previous.format() + ")");
			} else {
				r.value.setText(cur.format());
			}
		}
	}

	private void highlightSelection() {
		for (ResultRow r : resultRows) {
			r.view.setBackgroundColor(r.location.equals(selected) ? SELECTED_BG : Color.TRANSPARENT);
		}
	}

	// ================================================================== selected value panel

	private void setupSelectedPanel() {
		List<String> types = new ArrayList<>();
		for (ValueType t : TYPES) {
			types.add(t.label());
		}
		bind(b.spinSelType, types, ValueType.INT32.ordinal(), pos -> {
			if (updatingUi || selected == null || selected.scope != ScanScope.RAW) {
				return;
			}
			ValueType t = TYPES[pos];
			if (t == selected.type) {
				return;
			}
			int len = isOpaque(t) ? (selected.length > 0 ? selected.length : 4) : 0;
			selected = selected.withType(t, len);
			showSelectedValue(false);
		});
		b.btnRead.setOnClickListener(v -> readSelected());
		b.btnWrite.setOnClickListener(v -> writeSelected());
		b.btnFreeze.setOnClickListener(v -> freezeSelected());
		b.btnWatch.setOnClickListener(v -> watchSelected());
		b.btnCheat.setOnClickListener(v -> cheatSelected());
		b.btnViewer.setOnClickListener(v -> viewSelected());
		select(null);
	}

	private void select(@Nullable MemoryLocation loc) {
		selected = loc;
		boolean has = loc != null;
		b.btnRead.setEnabled(has);
		b.btnWrite.setEnabled(has);
		b.btnFreeze.setEnabled(has);
		b.btnWatch.setEnabled(has);
		b.btnCheat.setEnabled(has);
		b.btnViewer.setEnabled(has);
		b.spinSelType.setEnabled(has && loc.scope == ScanScope.RAW);
		if (!has) {
			b.selectedTitle.setText(R.string.memdbg_nothing_selected);
			b.selectedDetail.setText("");
			b.btnFreeze.setText(R.string.memdbg_freeze);
		} else {
			b.selectedTitle.setText(dbg.describe(loc));
			b.selectedDetail.setText(dbg.describeDetail(loc));
			updatingUi = true;
			b.spinSelType.setSelection(loc.type.ordinal(), false);
			updatingUi = false;
			showSelectedValue(true);
		}
		highlightSelection();
	}

	/** Reads the selection and shows it as the detail line (and, optionally, in the edit box). */
	private void showSelectedValue(boolean fillEditor) {
		if (selected == null) {
			return;
		}
		MemoryValue v = dbg.read(selected);
		String shown = v == null ? getString(R.string.memdbg_unavailable) : v.format();
		b.selectedDetail.setText(dbg.describeDetail(selected) + " · " + shown);
		if (fillEditor) {
			b.editNewValue.setText(v == null ? "" : v.format());
			b.editNewValue.setSelection(b.editNewValue.getText().length());
		}
	}

	private void readSelected() {
		if (selected == null) {
			return;
		}
		MemoryValue v = dbg.read(selected);
		showSelectedValue(true);
		toast(v == null ? getString(R.string.memdbg_unavailable)
				: getString(R.string.memdbg_read_value, v.format()));
	}

	private void writeSelected() {
		if (selected == null) {
			return;
		}
		try {
			MemoryValue v = MemoryValue.parse(selected.type, b.editNewValue.getText().toString(),
					selected.encoding);
			MemoryLocation loc = selected;
			if (selected.scope == ScanScope.RAW && isOpaque(selected.type)) {
				loc = selected.withType(selected.type, v.byteLength());
			}
			MemoryValue back = dbg.write(loc, v);
			selected = loc;
			toast(getString(R.string.memdbg_written, back.format()));
		} catch (IllegalArgumentException e) {
			toast(String.valueOf(e.getMessage()));
		} catch (UnavailableException e) {
			toast(R.string.memdbg_unavailable);
		}
		showSelectedValue(false);
		refreshResultValues();
		if (tab == TAB_MEMORY) {
			renderPage();
		}
	}

	private void resolveReference(final MemoryLocation loc, final RefCallback cb) {
		dbg.runAsync(() -> {
			MemoryReference ref = null;
			try {
				ref = dbg.referenceFor(loc);
			} catch (RuntimeException e) {
				// reported below
			}
			final MemoryReference result = ref;
			main.post(() -> {
				if (b == null || !isAdded()) {
					return;
				}
				if (result == null) {
					toast(R.string.memdbg_unavailable);
				} else {
					if (!result.isPersistent()) {
						toast(R.string.memdbg_session_only);
					}
					cb.done(result);
				}
			});
		});
	}

	private MemoryValue valueToApply(MemoryLocation loc) {
		String text = b.editNewValue.getText().toString();
		MemoryValue v = MemoryValue.parse(loc.type, text, loc.encoding);
		return v;
	}

	private void freezeSelected() {
		if (selected == null) {
			return;
		}
		final MemoryLocation loc;
		final MemoryValue value;
		try {
			MemoryValue parsed = valueToApply(selected);
			loc = selected.scope == ScanScope.RAW && isOpaque(selected.type)
					? selected.withType(selected.type, parsed.byteLength()) : selected;
			value = parsed;
		} catch (IllegalArgumentException e) {
			toast(String.valueOf(e.getMessage()));
			return;
		}
		resolveReference(loc, ref -> {
			MemoryFreeze existing = dbg.findFreeze(ref);
			if (existing != null && existing.isEnabled()) {
				dbg.removeFreeze(existing);
				b.btnFreeze.setText(R.string.memdbg_freeze);
				toast(R.string.memdbg_unfreeze);
				return;
			}
			if (existing != null) {
				dbg.removeFreeze(existing);
			}
			dbg.addFreeze(dbg.describe(loc), ref, loc.type, loc.bigEndian, loc.encoding, loc.length, value, true);
			b.btnFreeze.setText(R.string.memdbg_freeze_on);
			toast(getString(R.string.memdbg_frozen_at, value.format()));
		});
	}

	private void watchSelected() {
		if (selected == null) {
			return;
		}
		final MemoryLocation loc = selected;
		prompt(R.string.memdbg_name, dbg.describe(loc), name ->
				resolveReference(loc, ref -> {
					dbg.addWatch(name, loc, ref);
					toast(R.string.memdbg_added);
				}));
	}

	private void cheatSelected() {
		if (selected == null) {
			return;
		}
		final MemoryLocation base = selected;
		final MemoryValue value;
		final MemoryLocation loc;
		try {
			value = valueToApply(base);
			loc = base.scope == ScanScope.RAW && isOpaque(base.type)
					? base.withType(base.type, value.byteLength()) : base;
		} catch (IllegalArgumentException e) {
			toast(String.valueOf(e.getMessage()));
			return;
		}
		final EditText input = new EditText(requireContext());
		input.setSingleLine(true);
		input.setText(dbg.describe(loc));
		final CheckBox freeze = new CheckBox(requireContext());
		freeze.setText(R.string.memdbg_freeze_mode);
		freeze.setChecked(true);
		LinearLayout box = new LinearLayout(requireContext());
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(8), dp(20), 0);
		box.addView(input);
		box.addView(freeze);
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.memdbg_cheat)
				.setView(box)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					final String name = input.getText().toString();
					final boolean keep = freeze.isChecked();
					resolveReference(loc, ref -> {
						dbg.addCheat(name, ref, loc.type, loc.bigEndian, loc.encoding, loc.length, value, keep, true);
						toast(R.string.memdbg_added);
					});
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void viewSelected() {
		if (selected == null) {
			return;
		}
		try {
			view = dbg.openView(selected);
		} catch (IllegalArgumentException e) {
			toast(String.valueOf(e.getMessage()));
			return;
		}
		showTab(TAB_MEMORY);
		goToOffset(view.offset);
	}

	// ================================================================== memory pane

	private void setupMemoryPane() {
		buildHexRows();
		b.btnGo.setOnClickListener(v -> openFromInput());
		b.editAddress.setImeOptions(EditorInfo.IME_ACTION_GO);
		b.editAddress.setOnEditorActionListener((tv, action, event) -> {
			openFromInput();
			return true;
		});
		b.btnRegions.setOnClickListener(v -> showRegions());
		b.btnPrevPage.setOnClickListener(v -> movePage(-PAGE_BYTES));
		b.btnNextPage.setOnClickListener(v -> movePage(PAGE_BYTES));
		b.btnFind.setOnClickListener(v -> findInView());
		b.editFind.setOnEditorActionListener((tv, action, event) -> {
			findInView();
			return true;
		});
		b.btnWriteHex.setOnClickListener(v -> writeHex());
		renderPage();
	}

	private TextView mono(String text, int sp) {
		TextView t = new TextView(requireContext());
		t.setTypeface(Typeface.MONOSPACE);
		t.setTextSize(sp);
		t.setText(text);
		t.setTextColor(resolveColor(R.attr.textColorPrimary));
		return t;
	}

	private int resolveColor(int attr) {
		android.util.TypedValue tv = new android.util.TypedValue();
		requireContext().getTheme().resolveAttribute(attr, tv, true);
		return tv.data;
	}

	private void buildHexRows() {
		b.hexContainer.removeAllViews();
		addrViews = new TextView[ROWS];
		byteViews = new TextView[PAGE_BYTES];
		asciiViews = new TextView[ROWS];
		TextView header = mono("Address   +0 +1 +2 +3 +4 +5 +6 +7  ASCII", 10);
		b.hexContainer.addView(header);
		for (int r = 0; r < ROWS; r++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			TextView addr = mono("", 10);
			addr.setPadding(0, 0, dp(6), 0);
			addrViews[r] = addr;
			row.addView(addr);
			for (int c = 0; c < ROW_BYTES; c++) {
				final int index = r * ROW_BYTES + c;
				TextView cell = mono("  ", 11);
				cell.setPadding(dp(2), dp(3), dp(2), dp(3));
				cell.setOnClickListener(v -> {
					if (view != null) {
						selectOffset(pageStart + index);
					}
				});
				byteViews[index] = cell;
				row.addView(cell);
			}
			TextView ascii = mono("", 10);
			ascii.setPadding(dp(6), 0, 0, 0);
			asciiViews[r] = ascii;
			row.addView(ascii);
			b.hexContainer.addView(row);
		}
	}

	private void openFromInput() {
		try {
			view = dbg.openView(b.editAddress.getText().toString());
			goToOffset(view.offset);
		} catch (IllegalArgumentException | IllegalStateException e) {
			view = null;
			selOffset = -1;
			renderPage();
			b.viewStatus.setText(String.valueOf(e.getMessage()));
		}
	}

	private void goToOffset(int offset) {
		if (view == null) {
			return;
		}
		pageStart = (offset / PAGE_BYTES) * PAGE_BYTES;
		selectOffset(offset);
	}

	private void movePage(int delta) {
		if (view == null) {
			return;
		}
		int last = Math.max(0, ((view.size() - 1) / PAGE_BYTES) * PAGE_BYTES);
		pageStart = Math.max(0, Math.min(last, pageStart + delta));
		renderPage();
	}

	private void selectOffset(int offset) {
		selOffset = offset;
		if (view != null && view.raw) {
			ValueType t = selected != null && selected.scope == ScanScope.RAW ? selected.type : ValueType.INT32;
			int len = isOpaque(t) ? (selected != null && selected.length > 0 ? selected.length : 4) : 0;
			ScanParams p = dbg.settings().scan;
			select(new MemoryLocation(ScanScope.RAW, view.region.id(), offset, t, b.checkBigEndian.isChecked(),
					p.encoding, len));
		} else {
			select(null);
		}
		renderPage();
	}

	private void renderPage() {
		if (b == null || byteViews == null) {
			return;
		}
		if (view == null) {
			for (int i = 0; i < PAGE_BYTES; i++) {
				byteViews[i].setText("  ");
				byteViews[i].setBackgroundColor(Color.TRANSPARENT);
			}
			for (int r = 0; r < ROWS; r++) {
				addrViews[r].setText("");
				asciiViews[r].setText("");
			}
			b.viewInfo.setText(R.string.memdbg_view_empty);
			return;
		}
		byte[] data = dbg.readView(view, pageStart, PAGE_BYTES);
		if (data == null) {
			b.viewInfo.setText(R.string.memdbg_unavailable);
			return;
		}
		for (int r = 0; r < ROWS; r++) {
			long rowAddr = (long) pageStart + (long) r * ROW_BYTES;
			addrViews[r].setText(view.raw ? AddressSpace.format(view.base + rowAddr)
					: String.format(Locale.US, "+%07X", rowAddr));
			StringBuilder ascii = new StringBuilder();
			for (int c = 0; c < ROW_BYTES; c++) {
				int i = r * ROW_BYTES + c;
				TextView cell = byteViews[i];
				if (i < data.length) {
					int v = data[i] & 0xFF;
					cell.setText(String.format(Locale.US, "%02X", v));
					ascii.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
				} else {
					cell.setText("  ");
					ascii.append(' ');
				}
				cell.setBackgroundColor(pageStart + i == selOffset ? SELECTED_BG : Color.TRANSPARENT);
			}
			asciiViews[r].setText(ascii.toString());
		}
		int end = Math.min(view.size(), pageStart + PAGE_BYTES);
		b.viewInfo.setText(view.title + " · " + pageStart + "-" + Math.max(pageStart, end - 1)
				+ " / " + view.size());
		updateInterpretation();
	}

	private void updateInterpretation() {
		if (view == null || selOffset < 0) {
			b.viewStatus.setText("");
			return;
		}
		byte[] bytes = dbg.readView(view, selOffset, 8);
		if (bytes == null || bytes.length == 0) {
			b.viewStatus.setText(R.string.memdbg_unavailable);
			return;
		}
		String addr = view.raw ? AddressSpace.format(view.base + selOffset) + " (+" + selOffset + ")"
				: "+" + selOffset;
		b.viewStatus.setText("Address: " + addr + "\n"
				+ MemoryDebugger.interpret(bytes, 0, b.checkBigEndian.isChecked()));
	}

	private void findInView() {
		if (view == null) {
			toast(R.string.memdbg_view_empty);
			return;
		}
		String text = b.editFind.getText().toString().trim();
		byte[] needle;
		try {
			if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
				needle = StringEncoding.UTF8.encode(text.substring(1, text.length() - 1));
			} else {
				needle = MemoryValue.parseHex(text);
			}
		} catch (IllegalArgumentException e) {
			toast(String.valueOf(e.getMessage()));
			return;
		}
		int at = dbg.findInView(view, selOffset + 1, needle);
		if (at < 0 && selOffset >= 0) {
			at = dbg.findInView(view, 0, needle);
		}
		if (at < 0) {
			toast(R.string.memdbg_find_none);
		} else {
			goToOffset(at);
		}
	}

	private void writeHex() {
		if (view == null || selOffset < 0) {
			toast(R.string.memdbg_view_empty);
			return;
		}
		try {
			byte[] data = MemoryValue.parseHex(b.editHex.getText().toString());
			byte[] back = dbg.writeView(view, selOffset, data);
			toast(getString(R.string.memdbg_written, MemoryValue.toHex(back, 0, back.length)));
		} catch (IllegalArgumentException e) {
			toast(String.valueOf(e.getMessage()));
		} catch (UnavailableException e) {
			toast(R.string.memdbg_unavailable);
		}
		renderPage();
		refreshResultValues();
	}

	private void showRegions() {
		dbg.runAsync(() -> {
			final List<MemoryDebugger.RegionInfo> regions;
			try {
				regions = dbg.listRawRegions(500);
			} catch (RuntimeException e) {
				main.post(() -> toast(String.valueOf(e.getMessage())));
				return;
			}
			main.post(() -> {
				if (b == null || !isAdded()) {
					return;
				}
				if (regions.isEmpty()) {
					toast(R.string.memdbg_regions_empty);
					return;
				}
				String[] items = new String[regions.size()];
				for (int i = 0; i < items.length; i++) {
					MemoryDebugger.RegionInfo r = regions.get(i);
					items[i] = AddressSpace.format(r.address) + "  " + r.label + "  " + MemoryDebugger.idText(r.id);
				}
				new AlertDialog.Builder(requireContext())
						.setTitle(R.string.memdbg_regions)
						.setItems(items, (d, which) -> {
							b.editAddress.setText(AddressSpace.format(regions.get(which).address));
							openFromInput();
						})
						.show();
			});
		});
	}

	// ================================================================== watch / frozen / cheats

	private void setupFrozenPane() {
		b.editFreezePeriod.setText(String.valueOf(dbg.freezePeriodMs()));
		b.editFreezePeriod.setImeOptions(EditorInfo.IME_ACTION_DONE);
		b.editFreezePeriod.setOnEditorActionListener((tv, action, event) -> {
			applyFreezePeriod();
			return false;
		});
		b.editFreezePeriod.setOnFocusChangeListener((v, hasFocus) -> {
			if (!hasFocus) {
				applyFreezePeriod();
			}
		});
	}

	private void applyFreezePeriod() {
		try {
			dbg.setFreezePeriodMs(Integer.parseInt(b.editFreezePeriod.getText().toString().trim()));
			b.editFreezePeriod.setText(String.valueOf(dbg.freezePeriodMs()));
		} catch (NumberFormatException e) {
			toast(R.string.memdbg_invalid_number);
		}
	}

	private void renderTargets() {
		if (b == null) {
			return;
		}
		renderList(b.watchContainer, watchRows, new ArrayList<MemoryTarget>(dbg.watches()), TAB_WATCH);
		renderList(b.frozenContainer, frozenRows, new ArrayList<MemoryTarget>(dbg.freezes()), TAB_FROZEN);
		renderList(b.cheatsContainer, cheatRows, new ArrayList<MemoryTarget>(dbg.cheats()), TAB_CHEATS);
		refreshTargetValues();
	}

	private void renderList(LinearLayout container, List<TargetRow> rows, List<MemoryTarget> targets,
							final int kind) {
		container.removeAllViews();
		rows.clear();
		if (targets.isEmpty()) {
			TextView empty = new TextView(requireContext());
			empty.setText(R.string.memdbg_list_empty);
			empty.setPadding(dp(12), dp(12), dp(12), dp(12));
			empty.setTextColor(resolveColor(R.attr.textColorSecondary));
			container.addView(empty);
			return;
		}
		LayoutInflater inflater = LayoutInflater.from(requireContext());
		for (final MemoryTarget t : targets) {
			View row = inflater.inflate(R.layout.list_row_debug, container, false);
			((TextView) row.findViewById(R.id.row_title)).setText(t.name());
			String extra = kind == TAB_CHEATS ? " · " + (((MemoryCheat) t).isFreeze()
					? getString(R.string.memdbg_freeze_mode) : getString(R.string.memdbg_one_shot_mode)) : "";
			((TextView) row.findViewById(R.id.row_subtitle)).setText(t.ref().describe() + " · "
					+ t.type().label() + (t.isPersistent() ? "" : " · session") + extra);
			TextView value = row.findViewById(R.id.row_value);
			row.setOnClickListener(v -> {
				if (kind == TAB_WATCH) {
					watchMenu((MemoryWatch) t);
				} else if (kind == TAB_FROZEN) {
					freezeMenu((MemoryFreeze) t);
				} else {
					cheatMenu((MemoryCheat) t);
				}
			});
			container.addView(row);
			rows.add(new TargetRow(t, value));
		}
	}

	private void refreshTargetValues() {
		if (b == null) {
			return;
		}
		List<TargetRow> rows = tab == TAB_WATCH ? watchRows : tab == TAB_FROZEN ? frozenRows
				: tab == TAB_CHEATS ? cheatRows : Collections.<TargetRow>emptyList();
		for (TargetRow r : rows) {
			MemoryValue cur = dbg.read(r.target);
			String curText = cur == null ? getString(R.string.memdbg_unavailable) : cur.format();
			if (r.target instanceof MemoryFreeze) {
				MemoryFreeze f = (MemoryFreeze) r.target;
				r.value.setText(curText + "\n" + getString(R.string.memdbg_frozen_at, f.value().format())
						+ (f.isEnabled() ? " [ON]" : " [OFF]"));
			} else if (r.target instanceof MemoryCheat) {
				MemoryCheat c = (MemoryCheat) r.target;
				r.value.setText(curText + "\n" + getString(R.string.memdbg_cheat_sets, c.value().format())
						+ (c.isEnabled() ? " [ON]" : " [OFF]"));
			} else {
				r.value.setText(curText);
			}
		}
	}

	private void editTargetValue(final MemoryTarget t, final MemoryValue initial, final ValueCallback cb) {
		prompt(R.string.memdbg_edit_value, initial == null ? "" : initial.format(), text -> {
			try {
				cb.done(MemoryValue.parse(t.type(), text, t.encoding()));
			} catch (IllegalArgumentException e) {
				toast(String.valueOf(e.getMessage()));
			}
		});
	}

	private interface ValueCallback {
		void done(MemoryValue v);
	}

	private void watchMenu(final MemoryWatch w) {
		final String[] items = {
				getString(R.string.memdbg_edit_value), getString(R.string.memdbg_rename),
				getString(R.string.memdbg_change_type), getString(R.string.memdbg_freeze_value),
				getString(R.string.memdbg_remove)};
		new AlertDialog.Builder(requireContext())
				.setTitle(w.name())
				.setItems(items, (d, which) -> {
					switch (which) {
						case 0:
							editTargetValue(w, dbg.read(w), v -> {
								try {
									dbg.write(w, v);
								} catch (UnavailableException e) {
									toast(R.string.memdbg_unavailable);
								}
								refreshTargetValues();
							});
							break;
						case 1:
							prompt(R.string.memdbg_rename, w.name(), text -> {
								dbg.renameWatch(w, text);
							});
							break;
						case 2:
							chooseType(w);
							break;
						case 3:
							freezeFromWatch(w);
							break;
						default:
							dbg.removeWatch(w);
					}
				})
				.show();
	}

	private void chooseType(final MemoryWatch w) {
		final List<ValueType> options = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (ValueType t : TYPES) {
			// opaque types need a byte length, which only raw locations have
			if (!isOpaque(t) || t == ValueType.STRING) {
				options.add(t);
				labels.add(t.label());
			}
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.memdbg_change_type)
				.setItems(labels.toArray(new String[0]), (d, which) -> {
					ValueType t = options.get(which);
					dbg.retypeWatch(w, t, w.length());
				})
				.show();
	}

	private void freezeFromWatch(MemoryWatch w) {
		MemoryValue cur = dbg.read(w);
		if (cur == null) {
			toast(R.string.memdbg_unavailable);
			return;
		}
		MemoryFreeze existing = dbg.findFreeze(w.ref());
		if (existing != null) {
			dbg.removeFreeze(existing);
			toast(R.string.memdbg_unfreeze);
			return;
		}
		dbg.addFreeze(w.name(), w.ref(), w.type(), w.bigEndian(), w.encoding(), w.length(), cur, true);
		toast(getString(R.string.memdbg_frozen_at, cur.format()));
	}

	private void freezeMenu(final MemoryFreeze f) {
		final String[] items = {
				getString(R.string.memdbg_edit_value),
				getString(f.isEnabled() ? R.string.memdbg_disable : R.string.memdbg_enable),
				getString(R.string.memdbg_rename), getString(R.string.memdbg_remove)};
		new AlertDialog.Builder(requireContext())
				.setTitle(f.name())
				.setItems(items, (d, which) -> {
					switch (which) {
						case 0:
							editTargetValue(f, f.value(), v -> dbg.setFreezeValue(f, v));
							break;
						case 1:
							dbg.setFreezeEnabled(f, !f.isEnabled());
							break;
						case 2:
							prompt(R.string.memdbg_rename, f.name(), text -> dbg.renameFreeze(f, text));
							break;
						default:
							dbg.removeFreeze(f);
					}
				})
				.show();
	}

	private void cheatMenu(final MemoryCheat c) {
		final String[] items = {
				getString(c.isEnabled() ? R.string.memdbg_disable : R.string.memdbg_enable),
				getString(R.string.memdbg_edit_value),
				getString(c.isFreeze() ? R.string.memdbg_one_shot_mode : R.string.memdbg_freeze_mode),
				getString(R.string.memdbg_rename), getString(R.string.memdbg_remove)};
		new AlertDialog.Builder(requireContext())
				.setTitle(c.name())
				.setItems(items, (d, which) -> {
					switch (which) {
						case 0:
							dbg.setCheatEnabled(c, !c.isEnabled());
							break;
						case 1:
							editTargetValue(c, c.value(), v -> dbg.setCheatValue(c, v));
							break;
						case 2:
							dbg.setCheatFreeze(c, !c.isFreeze());
							break;
						case 3:
							prompt(R.string.memdbg_rename, c.name(), text -> dbg.renameCheat(c, text));
							break;
						default:
							dbg.removeCheat(c);
					}
				})
				.show();
	}
}
