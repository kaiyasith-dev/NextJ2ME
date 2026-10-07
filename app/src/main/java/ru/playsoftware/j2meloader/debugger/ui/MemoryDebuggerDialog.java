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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.databinding.DialogMemoryDebuggerBinding;
import ru.playsoftware.j2meloader.debugger.AddressSpace;
import ru.playsoftware.j2meloader.debugger.MemoryDebugger;
import ru.playsoftware.j2meloader.debugger.MemoryFreeze;
import ru.playsoftware.j2meloader.debugger.MemoryLocation;
import ru.playsoftware.j2meloader.debugger.MemoryRegion;
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

	private static final int RESULT_PAGE = 50;
	private static final int ROW_BYTES = 8;
	private static final int ROWS = 32;
	private static final int PAGE_BYTES = ROW_BYTES * ROWS;
	/** Rows added each time "Show more" is pressed in list mode. */
	private static final int LIST_ROWS = 64;
	/** Most rows shown at once; beyond this the window slides instead of growing. */
	private static final int LIST_MAX_ROWS = 1024;
	/** Rows shown above the selected value when a value is opened. */
	private static final int LIST_CONTEXT = 8;
	private static final long REFRESH_MS = 500;
	private static final int SELECTED_BG = 0x44808080;

	private static final ScanScope[] SCOPES = ScanScope.values();
	private static final ValueType[] TYPES = ValueType.values();
	private static final ScanMode[] MODES = ScanMode.values();
	private static final StringEncoding[] ENCODINGS = StringEncoding.values();
	private static final int[] ALIGNMENTS = {0, 1, 2, 4, 8};
	/** Types the memory view can read and edit at the selected byte. */
	private static final ValueType[] VIEW_TYPES = {
			ValueType.INT8, ValueType.UINT8, ValueType.INT16, ValueType.UINT16, ValueType.INT32,
			ValueType.UINT32, ValueType.INT64, ValueType.UINT64, ValueType.FLOAT, ValueType.DOUBLE,
			ValueType.BOOLEAN, ValueType.STRING};

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

	private static final class ListRow {
		final View view;
		final TextView title;
		final TextView sub;
		final TextView value;

		ListRow(View view, TextView title, TextView sub, TextView value) {
			this.view = view;
			this.title = title;
			this.sub = sub;
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
	private ValueType viewType = ValueType.INT32;
	private int viewLength = 8;
	private TextView[] addrViews;
	private TextView[] byteViews;
	private TextView[] asciiViews;
	private boolean listMode = true;
	private final List<ListRow> listRowViews = new ArrayList<>();
	/** Offset of the first value shown in list mode, and how many values are shown. */
	private int listStart;
	private int listCount = LIST_ROWS;

	// lists
	private final List<TargetRow> watchRows = new ArrayList<>();
	private final List<TargetRow> frozenRows = new ArrayList<>();

	// the tab bar hides while scrolling down and returns when scrolling up
	private boolean tabBarShown = true;
	private boolean tabBarAnimating;
	private int tabBarFullHeight;
	private int lastScrollY;
	private int scrollDownAccum;
	private ViewTreeObserver scrollObserver;
	private final ViewTreeObserver.OnScrollChangedListener scrollListener =
			new ViewTreeObserver.OnScrollChangedListener() {
				@Override
				public void onScrollChanged() {
					onContentScrolled();
				}
			};

	private static final int TAB_BG_CURRENT = 0xFF000000;
	private static final int TAB_BG_OTHER = 0xFF3C3C3C;

	private TextView[] tabButtons;

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
			scrollObserver = b.getRoot().getViewTreeObserver();
			scrollObserver.addOnScrollChangedListener(scrollListener);
			dbg.addListener(this);
			structural.run();
			main.removeCallbacks(ticker);
			main.postDelayed(ticker, REFRESH_MS);
		}
	}

	@Override
	public void onStop() {
		if (scrollObserver != null && scrollObserver.isAlive()) {
			scrollObserver.removeOnScrollChangedListener(scrollListener);
		}
		scrollObserver = null;
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

	// ================================================================== header and tabs

	private void setupHeader() {
		b.btnClose.setOnClickListener(v -> dismiss());
		b.tabScan.setOnClickListener(v -> showTab(TAB_SCAN));
		b.tabMemory.setOnClickListener(v -> showTab(TAB_MEMORY));
		b.tabWatch.setOnClickListener(v -> showTab(TAB_WATCH));
		b.tabFrozen.setOnClickListener(v -> showTab(TAB_FROZEN));
		tabButtons = new TextView[]{b.tabScan, b.tabMemory, b.tabWatch, b.tabFrozen};
	}

	private ScrollView scrollViewForTab(int t) {
		switch (t) {
			case TAB_MEMORY:
				return b.paneMemory;
			case TAB_WATCH:
				return b.paneWatch;
			case TAB_FROZEN:
				return b.paneFrozen;
			default:
				return b.paneScan;
		}
	}

	/** Hides the tab bar after scrolling down a bit, shows it on any scroll up or at the top. */
	private void onContentScrolled() {
		if (b == null || tabBarAnimating) {
			return;
		}
		ScrollView sv = scrollViewForTab(tab);
		View content = sv.getChildAt(0);
		if (content == null) {
			return;
		}
		int y = sv.getScrollY();
		int dy = y - lastScrollY;
		lastScrollY = y;
		if (tabBarShown) {
			scrollDownAccum = dy > 0 ? scrollDownAccum + dy : 0;
			int full = b.tabBar.getHeight();
			// only hide when there is clearly more to scroll than the bar takes: the bar leaving
			// makes the area taller, and a short list could otherwise bounce back and forth
			boolean enoughToScroll = content.getHeight() - sv.getHeight() > 2 * full;
			if (scrollDownAccum > dp(24) && y > full && enoughToScroll) {
				tabBarFullHeight = full;
				setTabBarVisible(false, true);
			}
		} else if (dy < -dp(6) || y <= 0) {
			setTabBarVisible(true, true);
		}
	}

	private void setTabBarVisible(boolean visible, boolean animate) {
		if (b == null || visible == tabBarShown) {
			return;
		}
		tabBarShown = visible;
		final LinearLayout bar = b.tabBar;
		final ViewGroup.LayoutParams lp = bar.getLayoutParams();
		final int full = tabBarFullHeight > 0 ? tabBarFullHeight : bar.getHeight();
		if (!animate) {
			lp.height = visible ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
			bar.setVisibility(visible ? View.VISIBLE : View.GONE);
			bar.setLayoutParams(lp);
			return;
		}
		bar.setVisibility(View.VISIBLE);
		tabBarAnimating = true;
		ValueAnimator animator = ValueAnimator.ofInt(visible ? 0 : full, visible ? full : 0);
		animator.setDuration(150);
		animator.addUpdateListener(a -> {
			lp.height = (Integer) a.getAnimatedValue();
			bar.setLayoutParams(lp);
		});
		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				if (b == null) {
					return;
				}
				if (visible) {
					lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
				} else {
					bar.setVisibility(View.GONE);
				}
				bar.setLayoutParams(lp);
				// the resize may have moved the scroll position; do not treat that as user input
				lastScrollY = scrollViewForTab(tab).getScrollY();
				scrollDownAccum = 0;
				tabBarAnimating = false;
			}
		});
		animator.start();
	}

	private void showTab(int t) {
		tab = t;
		b.paneScan.setVisibility(t == TAB_SCAN ? View.VISIBLE : View.GONE);
		b.paneMemory.setVisibility(t == TAB_MEMORY ? View.VISIBLE : View.GONE);
		b.paneWatch.setVisibility(t == TAB_WATCH ? View.VISIBLE : View.GONE);
		b.paneFrozen.setVisibility(t == TAB_FROZEN ? View.VISIBLE : View.GONE);
		setTabBarVisible(true, false);
		lastScrollY = scrollViewForTab(t).getScrollY();
		scrollDownAccum = 0;
		// tabs look like buttons with white text: the current one is black, the others dark grey
		for (int i = 0; i < tabButtons.length; i++) {
			boolean current = i == t;
			tabButtons[i].setBackgroundColor(current ? TAB_BG_CURRENT : TAB_BG_OTHER);
			tabButtons[i].setTextColor(Color.WHITE);
			tabButtons[i].setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);
			tabButtons[i].setAlpha(current ? 1f : 0.75f);
		}
		renderTargets();
		refreshVisible();
	}

	private void updateHeader() {
		if (b == null || dbg == null) {
			return;
		}
		b.stateText.setText(dbg.statsText());
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
		b.checkGroup.setChecked(p.group);
		b.editGroupWindow.setText(String.valueOf(p.groupWindow));
		b.checkGroupOrdered.setChecked(p.groupOrdered);
		b.checkGroup.setOnCheckedChangeListener((button, checked) -> {
			if (checked) {
				// a group scan is always an exact-value scan
				b.spinMode.setSelection(ScanMode.EXACT.ordinal());
			}
			updateValueField();
		});
		updateValueField();

		b.btnNewScan.setOnClickListener(v -> startScan(true));
		b.btnNextScan.setOnClickListener(v -> startScan(false));
		b.btnResetScan.setOnClickListener(v -> confirmReset());
		b.btnCancelScan.setOnClickListener(v -> dbg.cancelScan());
		b.btnHistory.setOnClickListener(v -> showHistory());
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
		boolean group = b.checkGroup.isChecked() && type.isNumeric();
		b.editValue.setVisibility(mode.needsValue() || group ? View.VISIBLE : View.GONE);
		b.editValue.setHint(group ? R.string.memdbg_group_hint
				: type == ValueType.BYTES ? R.string.memdbg_value_hint_bytes
				: type == ValueType.STRING ? R.string.memdbg_value_hint_text : R.string.memdbg_value);
		b.groupOptions.setVisibility(b.checkGroup.isChecked() ? View.VISIBLE : View.GONE);
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
		p.group = b.checkGroup.isChecked();
		p.groupOrdered = b.checkGroupOrdered.isChecked();
		try {
			p.groupWindow = Integer.parseInt(b.editGroupWindow.getText().toString().trim());
		} catch (NumberFormatException e) {
			p.groupWindow = ScanParams.DEFAULT_GROUP_WINDOW;
		}
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
		if (!first) {
			if (p.value.contains(";")) {
				toast(R.string.memdbg_group_new_only);
				return;
			}
			p.group = false; // groups only start a scan; the next scans filter its results
		}
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
		b.sessionRow.setVisibility(sessions.isEmpty() ? View.GONE : View.VISIBLE);
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

	/** Resetting throws away the results and the history, so it needs a confirmation. */
	private void confirmReset() {
		if (dbg.activeSession() == null) {
			b.scanStatus.setText(R.string.memdbg_no_scan);
			return;
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.memdbg_reset_confirm_title)
				.setMessage(R.string.memdbg_reset_confirm_message)
				.setPositiveButton(R.string.memdbg_reset_scan, (d, w) -> {
					dbg.resetScan();
					resultLimit = RESULT_PAGE;
					b.scanStatus.setText("");
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/** Lists the steps of the active scan; choosing one goes back to the results it had. */
	private void showHistory() {
		final ScanSession s = dbg.activeSession();
		if (s == null || s.history().isEmpty()) {
			toast(R.string.memdbg_history_empty);
			return;
		}
		final List<ScanSession.Step> steps = s.history();
		String[] items = new String[steps.size()];
		for (int i = 0; i < items.length; i++) {
			ScanSession.Step step = steps.get(i);
			String mark = i == items.length - 1 ? "  \u2190 " + getString(R.string.memdbg_history_now)
					: step.isRestorable() ? "" : "  (" + getString(R.string.memdbg_history_not_kept) + ")";
			items[i] = (i + 1) + ". " + step + mark;
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.memdbg_history_title)
				.setItems(items, (d, which) -> restoreStep(s, which, steps.size()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void restoreStep(ScanSession s, int index, int stepCount) {
		if (index == stepCount - 1) {
			return; // already the current results
		}
		try {
			dbg.restoreScanStep(s, index);
		} catch (IllegalArgumentException | IllegalStateException e) {
			toast(String.valueOf(e.getMessage()));
			return;
		}
		resultLimit = RESULT_PAGE;
		shownSession = null;
		syncResultsWithSession();
		refreshSessions();
		toast(getString(R.string.memdbg_history_restored, index + 1, (int) s.resultCount()));
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
			row.setOnClickListener(v -> showLocationMenu(loc));
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

	// ================================================================== value pop-up

	/** Remembers the selection (used by the pop-up and highlighted in the results list). */
	private void select(@Nullable MemoryLocation loc) {
		selected = loc;
		highlightSelection();
	}

	/**
	 * Pop-up with everything that can be done with a value found by a scan or selected in the
	 * memory view: edit it, freeze it, watch it, show it in the memory view, change its type.
	 */
	private void showLocationMenu(final MemoryLocation loc) {
		select(loc);
		MemoryValue cur = dbg.read(loc);
		final String curText = cur == null ? "" : cur.format();
		String title = dbg.describe(loc) + "\n" + dbg.describeDetail(loc) + " \u00b7 "
				+ (cur == null ? getString(R.string.memdbg_unavailable) : curText);
		final boolean raw = loc.scope == ScanScope.RAW;
		List<String> labels = new ArrayList<>();
		labels.add(getString(R.string.memdbg_edit_value));
		labels.add(getString(R.string.memdbg_freeze));
		labels.add(getString(R.string.memdbg_watch));
		labels.add(getString(R.string.memdbg_open_in_memory));
		if (raw) {
			labels.add(getString(R.string.memdbg_change_type));
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(title)
				.setItems(labels.toArray(new String[0]), (d, which) -> {
					switch (which) {
						case 0:
							prompt(R.string.memdbg_edit_value, curText, text -> writeLocation(loc, text));
							break;
						case 1:
							prompt(R.string.memdbg_freeze, curText, text -> freezeAt(loc, text));
							break;
						case 2:
							watchLocation(loc);
							break;
						case 3:
							openInViewer(loc);
							break;
						default:
							chooseLocationType(loc);
					}
				})
				.show();
	}

	/** The location with its byte length set from the value, for text and byte sequences in raw memory. */
	private static MemoryLocation sized(MemoryLocation loc, MemoryValue v) {
		return loc.scope == ScanScope.RAW && isOpaque(loc.type) ? loc.withType(loc.type, v.byteLength()) : loc;
	}

	private void writeLocation(MemoryLocation base, String text) {
		try {
			MemoryValue v = MemoryValue.parse(base.type, text, base.encoding);
			MemoryLocation loc = sized(base, v);
			MemoryValue back = dbg.write(loc, v);
			select(loc);
			toast(getString(R.string.memdbg_written, back.format()));
		} catch (IllegalArgumentException e) {
			toast(String.valueOf(e.getMessage()));
		} catch (UnavailableException e) {
			toast(R.string.memdbg_unavailable);
		}
		refreshResultValues();
		if (tab == TAB_MEMORY) {
			renderPage();
		}
	}

	private void freezeAt(MemoryLocation base, String text) {
		final MemoryLocation loc;
		final MemoryValue value;
		try {
			value = MemoryValue.parse(base.type, text, base.encoding);
			loc = sized(base, value);
		} catch (IllegalArgumentException e) {
			toast(String.valueOf(e.getMessage()));
			return;
		}
		resolveReference(loc, ref -> {
			MemoryFreeze existing = dbg.findFreeze(ref);
			if (existing != null) {
				dbg.removeFreeze(existing); // freezing again replaces the old value
			}
			dbg.addFreeze(dbg.describe(loc), ref, loc.type, loc.bigEndian, loc.encoding, loc.length, value, true);
			toast(getString(R.string.memdbg_frozen_at, value.format()));
		});
	}

	private void watchLocation(final MemoryLocation loc) {
		prompt(R.string.memdbg_name, dbg.describe(loc), name ->
				resolveReference(loc, ref -> {
					dbg.addWatch(name, loc, ref);
					toast(R.string.memdbg_added);
				}));
	}

	private void chooseLocationType(final MemoryLocation loc) {
		String[] labels = new String[TYPES.length];
		for (int i = 0; i < TYPES.length; i++) {
			labels[i] = TYPES[i].label();
		}
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.memdbg_change_type)
				.setItems(labels, (d, which) -> {
					ValueType t = TYPES[which];
					int len = isOpaque(t) ? (loc.length > 0 ? loc.length : 4) : 0;
					showLocationMenu(loc.withType(t, len));
				})
				.show();
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

	/** Jumps to the memory view at the location of a watch or freeze. */
	private void openInViewer(MemoryTarget t) {
		MemoryLocation loc = dbg.locate(t);
		if (loc == null) {
			toast(R.string.memdbg_unavailable);
			return;
		}
		openInViewer(loc);
	}

	/** Shows the memory view at a location, read with the location type and byte order. */
	private void openInViewer(MemoryLocation loc) {
		if (loc.type == ValueType.STRING && loc.scope != ScanScope.RAW) {
			toast(R.string.memdbg_no_bytes);
			return;
		}
		try {
			view = dbg.openView(loc);
		} catch (RuntimeException e) {
			toast(R.string.memdbg_unavailable);
			return;
		}
		showTab(TAB_MEMORY);
		syncViewType(loc.type);
		if (loc.type == ValueType.STRING && loc.length > 0) {
			viewLength = loc.length;
			b.editViewLength.setText(String.valueOf(viewLength));
		}
		b.checkViewBe.setChecked(loc.bigEndian);
		goToOffset(view.offset);
		select(loc); // keeps the real type of the value for the pop-up
	}

	// ================================================================== memory pane

	private void setupMemoryPane() {
		buildHexRows();
		b.btnRegions.setOnClickListener(v -> showRegions());
		b.btnListEarlier.setOnClickListener(v -> showEarlier());
		b.btnListMore.setOnClickListener(v -> showMore());
		bind(b.spinViewMode, Arrays.asList(getString(R.string.memdbg_mode_hex), getString(R.string.memdbg_mode_list)),
				listMode ? 1 : 0, pos -> {
					boolean list = pos == 1;
					if (list != listMode) {
						listMode = list;
						realignPage();
						renderPage();
					}
				});

		List<String> viewTypes = new ArrayList<>();
		for (ValueType t : VIEW_TYPES) {
			viewTypes.add(t.label());
		}
		bind(b.spinViewType, viewTypes, viewTypeIndex(viewType), pos -> {
			if (VIEW_TYPES[pos] != viewType) {
				setViewType(VIEW_TYPES[pos]);
			}
		});
		b.checkViewBe.setChecked(dbg.settings().scan.bigEndian);
		b.checkViewBe.setOnCheckedChangeListener((button, checked) -> refreshSelection());
		b.editViewLength.setText(String.valueOf(viewLength));
		b.editViewLength.setImeOptions(EditorInfo.IME_ACTION_DONE);
		b.editViewLength.setOnEditorActionListener((tv, action, event) -> {
			refreshSelection();
			return false;
		});
		b.editViewLength.setOnFocusChangeListener((v, hasFocus) -> {
			if (!hasFocus) {
				refreshSelection();
			}
		});
		b.btnViewMore.setOnClickListener(v -> {
			if (selected == null) {
				toast(R.string.memdbg_nothing_selected);
			} else {
				showLocationMenu(selected);
			}
		});
		renderPage();
	}

	private static int viewTypeIndex(ValueType t) {
		for (int i = 0; i < VIEW_TYPES.length; i++) {
			if (VIEW_TYPES[i] == t) {
				return i;
			}
		}
		return -1;
	}

	private boolean viewBigEndian() {
		return b.checkViewBe.isChecked();
	}

	/** Length in bytes of a String read or write, from the length box (1..256). */
	private int readViewLength() {
		try {
			viewLength = Math.max(1, Math.min(256, Integer.parseInt(b.editViewLength.getText().toString().trim())));
		} catch (NumberFormatException e) {
			// keep the last valid length
		}
		return viewLength;
	}

	/** Number of bytes the selected data type covers. */
	private int viewWidth() {
		return viewType == ValueType.STRING ? readViewLength() : viewType.width();
	}

	/** Sets the type without re-reading anything (used when another control already did). */
	private void syncViewType(ValueType t) {
		int idx = viewTypeIndex(t);
		if (idx < 0) {
			return;
		}
		viewType = t;
		updatingUi = true;
		b.spinViewType.setSelection(idx, false);
		updatingUi = false;
		b.editViewLength.setVisibility(t == ValueType.STRING ? View.VISIBLE : View.GONE);
	}

	private void setViewType(ValueType t) {
		syncViewType(t);
		refreshSelection();
	}

	/** Re-reads the selected byte with the current type and byte order. */
	private void refreshSelection() {
		if (b == null) {
			return;
		}
		realignPage();
		if (view != null && selOffset >= 0) {
			selectOffset(selOffset);
		} else {
			renderPage();
		}
	}

	/** Text of the bytes interpreted as the selected type, or null if too few bytes are available. */
	@Nullable
	private String viewValueText(byte[] bytes) {
		return viewValueTextAt(bytes, 0);
	}

	/** Like {@link #viewValueText} for the value that starts at {@code off} of {@code bytes}. */
	@Nullable
	private String viewValueTextAt(byte[] bytes, int off) {
		int width = viewWidth();
		if (bytes == null || bytes.length - off < width) {
			return null;
		}
		if (viewType == ValueType.STRING) {
			int end = 0;
			while (end < width && bytes[off + end] != 0) {
				end++; // text ends at the first zero byte
			}
			return StringEncoding.UTF8.decode(Arrays.copyOfRange(bytes, off, off + end));
		}
		return viewType.format(viewType.fromBytes(bytes, off, viewBigEndian()));
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

	/** Opens the raw array that starts at {@code address} in the viewer. */
	private void openRegion(long address) {
		try {
			view = dbg.openView(AddressSpace.format(address));
			goToOffset(view.offset);
		} catch (IllegalArgumentException | IllegalStateException e) {
			view = null;
			selOffset = -1;
			renderPage();
			b.viewStatus.setText(String.valueOf(e.getMessage()));
		}
	}

	private void selectOffset(int offset) {
		selOffset = offset;
		if (view != null && view.raw) {
			int len = viewType == ValueType.STRING ? readViewLength() : 0;
			select(new MemoryLocation(ScanScope.RAW, view.region.id(), offset, viewType, viewBigEndian(),
					dbg.settings().scan.encoding, len));
		} else {
			// an object, class or typed array image: select the field or element under the byte
			select(locationAt(offset));
		}
		renderPage();
	}

	/**
	 * The field (class or object view) or element (typed array view) that contains the byte at
	 * {@code offset} of the viewed image, or null if there is none (padding, String fields).
	 */
	@Nullable
	private MemoryLocation locationAt(int offset) {
		if (view == null) {
			return null;
		}
		try {
			MemoryRegion r = view.region;
			ScanScope scope = r.kind() == MemoryRegion.Kind.STATIC ? ScanScope.STATIC_FIELDS
					: r.kind() == MemoryRegion.Kind.OBJECT ? ScanScope.OBJECTS : ScanScope.ARRAYS;
			int slot = -1;
			int count = r.slotCount();
			if (r.kind() == MemoryRegion.Kind.ARRAY) {
				if (count > 0) {
					int width = r.imageSize() / count;
					slot = width > 0 ? offset / width : -1;
				}
			} else {
				for (int i = 0; i < count; i++) {
					int start = r.imageOffset(i);
					if (start >= 0 && offset >= start && offset < start + r.slotType(i).width()) {
						slot = i;
						break;
					}
				}
			}
			if (slot < 0 || slot >= count) {
				return null;
			}
			return new MemoryLocation(scope, r.id(), slot, r.slotType(slot), viewBigEndian(),
					dbg.settings().scan.encoding, 0);
		} catch (RuntimeException e) {
			return null; // the region disappeared
		}
	}

	/** Name of the row at {@code off}: the address, a field name, or an array index. */
	private String listRowName(int off) {
		if (view.raw) {
			return AddressSpace.format(view.base + off);
		}
		try {
			MemoryRegion r = view.region;
			int count = r.slotCount();
			if (r.kind() == MemoryRegion.Kind.ARRAY) {
				int w = count > 0 ? r.imageSize() / count : 0;
				if (w > 0) {
					return "[" + (off / w) + "]" + (off % w != 0 ? "+" + (off % w) : "");
				}
			} else {
				for (int i = 0; i < count; i++) {
					int start = r.imageOffset(i);
					if (start >= 0 && off >= start && off < start + r.slotType(i).width()) {
						return off == start ? r.slotName(i) : r.slotName(i) + "+" + (off - start);
					}
				}
			}
		} catch (RuntimeException e) {
			// the region disappeared: fall through to a plain offset
		}
		return String.format(Locale.US, "+%X", off);
	}

	private int listWidth() {
		return Math.max(1, viewWidth());
	}

	/** Positions the hex page and the list window so that {@code offset} is on screen, with some context. */
	private void setWindowAround(int offset) {
		pageStart = (offset / PAGE_BYTES) * PAGE_BYTES;
		int w = listWidth();
		listStart = Math.max(0, offset / w - LIST_CONTEXT) * w;
		listCount = LIST_ROWS;
	}

	/** Re-centres the window after the type, byte order or mode changed. */
	private void realignPage() {
		if (view != null) {
			setWindowAround(selOffset >= 0 ? selOffset : (listMode ? listStart : pageStart));
		}
	}

	private void goToOffset(int offset) {
		if (view == null) {
			return;
		}
		setWindowAround(offset);
		selectOffset(offset);
	}

	/** Shows more values above (list) or the previous page (hex). */
	private void showEarlier() {
		if (view == null) {
			return;
		}
		if (listMode) {
			int w = listWidth();
			int add = Math.min(LIST_ROWS, listStart / w);
			listStart -= add * w;
			listCount = Math.min(LIST_MAX_ROWS, listCount + add);
		} else {
			pageStart = Math.max(0, pageStart - PAGE_BYTES);
		}
		renderPage();
	}

	/** Shows more values below (list) or the next page (hex). */
	private void showMore() {
		if (view == null) {
			return;
		}
		if (listMode) {
			int w = listWidth();
			if (listCount >= LIST_MAX_ROWS) {
				int lastRow = Math.max(0, view.size() / w - 1);
				listStart = Math.min(listStart + LIST_ROWS * w, lastRow * w); // slide the window down
			} else {
				listCount += LIST_ROWS;
			}
		} else {
			int last = Math.max(0, ((view.size() - 1) / PAGE_BYTES) * PAGE_BYTES);
			pageStart = Math.min(last, pageStart + PAGE_BYTES);
		}
		renderPage();
	}

	private void renderPage() {
		if (b == null || byteViews == null) {
			return;
		}
		boolean has = view != null;
		b.viewEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
		b.listContainer.setVisibility(has && listMode ? View.VISIBLE : View.GONE);
		b.hexContainer.setVisibility(has && !listMode ? View.VISIBLE : View.GONE);
		// the selected value summary and the conversion table are for the hex grid only
		b.viewValue.setVisibility(!listMode ? View.VISIBLE : View.GONE);
		b.viewStatus.setVisibility(!listMode ? View.VISIBLE : View.GONE);
		if (!has) {
			b.btnListEarlier.setVisibility(View.GONE);
			b.btnListMore.setVisibility(View.GONE);
			b.viewInfo.setText(R.string.memdbg_view_empty);
			b.viewValue.setText("");
			b.viewStatus.setText("");
			return;
		}
		int w = listWidth();
		boolean earlier = listMode ? listStart > 0 : pageStart > 0;
		boolean more = listMode ? (long) listStart + (long) listCount * w + w <= view.size()
				: pageStart + PAGE_BYTES < view.size();
		b.btnListEarlier.setVisibility(earlier ? View.VISIBLE : View.GONE);
		b.btnListMore.setVisibility(more ? View.VISIBLE : View.GONE);
		if (listMode) {
			renderList();
		} else {
			renderHexPage();
		}
	}

	/** Makes sure at least {@code n} list rows exist (they are created once and reused). */
	private void ensureListRows(int n) {
		LayoutInflater inflater = LayoutInflater.from(requireContext());
		while (listRowViews.size() < n) {
			final int index = listRowViews.size();
			View row = inflater.inflate(R.layout.list_row_debug, b.listContainer, false);
			row.setOnClickListener(v -> onListRowClicked(index));
			b.listContainer.addView(row);
			listRowViews.add(new ListRow(row, (TextView) row.findViewById(R.id.row_title),
					(TextView) row.findViewById(R.id.row_subtitle), (TextView) row.findViewById(R.id.row_value)));
		}
	}

	private void onListRowClicked(int index) {
		if (view == null) {
			return;
		}
		selectOffset(listStart + index * listWidth());
		if (selected != null) {
			showLocationMenu(selected);
		} else {
			toast(R.string.memdbg_nothing_selected);
		}
	}

	/** One row per value of the selected type, starting at {@link #listStart}. */
	private void renderList() {
		int width = listWidth();
		int size = view.size();
		int rows = Math.min(listCount, Math.max(0, (size - listStart) / width));
		byte[] data = dbg.readView(view, listStart, rows * width);
		if (data == null) {
			b.viewInfo.setText(R.string.memdbg_unavailable);
			return;
		}
		ensureListRows(rows);
		for (int r = 0; r < listRowViews.size(); r++) {
			ListRow row = listRowViews.get(r);
			int rel = r * width;
			if (r >= rows || rel + width > data.length) {
				row.view.setVisibility(View.GONE);
				continue;
			}
			row.view.setVisibility(View.VISIBLE);
			int off = listStart + rel;
			row.title.setText(listRowName(off));
			String hex = MemoryValue.toHex(data, rel, Math.min(width, 8)) + (width > 8 ? " \u2026" : "");
			row.sub.setText(String.format(Locale.US, "+%X \u00b7 %s", off, hex));
			String text = viewValueTextAt(data, rel);
			row.value.setText(text == null ? getString(R.string.memdbg_unavailable)
					: viewType == ValueType.STRING ? "\"" + text + "\"" : text);
			row.view.setBackgroundColor(selOffset >= off && selOffset < off + width ? SELECTED_BG : Color.TRANSPARENT);
		}
		int first = listStart / width;
		b.viewInfo.setText(view.title + " \u00b7 " + (first + 1) + "\u2013" + (first + rows) + " of " + (size / width));
	}

	private void renderHexPage() {
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
		int selEnd = selOffset < 0 ? -1 : selOffset + Math.max(1, viewWidth());
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
				int abs = pageStart + i;
				cell.setBackgroundColor(selOffset >= 0 && abs >= selOffset && abs < selEnd
						? SELECTED_BG : Color.TRANSPARENT);
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
			b.viewValue.setText("");
			return;
		}
		byte[] bytes = dbg.readView(view, selOffset, Math.max(8, viewWidth()));
		if (bytes == null || bytes.length == 0) {
			b.viewStatus.setText(R.string.memdbg_unavailable);
			b.viewValue.setText("");
			return;
		}
		String addr = view.raw ? AddressSpace.format(view.base + selOffset) + " (+" + selOffset + ")"
				: "+" + selOffset;
		String text = viewValueText(bytes);
		String hex = viewType.isNumeric() && text != null
				? "  " + viewType.formatHex(viewType.fromBytes(bytes, 0, viewBigEndian())) : "";
		b.viewValue.setText(viewType.label() + " @ " + addr + " = "
				+ (text == null ? getString(R.string.memdbg_unavailable) : viewType == ValueType.STRING
				? "\"" + text + "\"" : text) + hex);
		b.viewStatus.setText(MemoryDebugger.interpret(bytes, 0, viewBigEndian()));
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
							openRegion(regions.get(which).address);
						})
						.show();
			});
		});
	}

	// ================================================================== watch / frozen

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
			((TextView) row.findViewById(R.id.row_subtitle)).setText(t.ref().describe() + " · "
					+ t.type().label() + (t.isPersistent() ? "" : " · session"));
			TextView value = row.findViewById(R.id.row_value);
			row.setOnClickListener(v -> {
				if (kind == TAB_WATCH) {
					watchMenu((MemoryWatch) t);
				} else {
					freezeMenu((MemoryFreeze) t);
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
				: Collections.<TargetRow>emptyList();
		for (TargetRow r : rows) {
			MemoryValue cur = dbg.read(r.target);
			String curText = cur == null ? getString(R.string.memdbg_unavailable) : cur.format();
			if (r.target instanceof MemoryFreeze) {
				MemoryFreeze f = (MemoryFreeze) r.target;
				r.value.setText(curText + "\n" + getString(R.string.memdbg_frozen_at, f.value().format())
						+ (f.isEnabled() ? " [ON]" : " [OFF]"));
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
				getString(R.string.memdbg_open_in_memory),
				getString(R.string.memdbg_edit_value), getString(R.string.memdbg_rename),
				getString(R.string.memdbg_change_type), getString(R.string.memdbg_freeze_value),
				getString(R.string.memdbg_remove)};
		new AlertDialog.Builder(requireContext())
				.setTitle(w.name())
				.setItems(items, (d, which) -> {
					switch (which) {
						case 0:
							openInViewer(w);
							break;
						case 1:
							editTargetValue(w, dbg.read(w), v -> {
								try {
									dbg.write(w, v);
								} catch (UnavailableException e) {
									toast(R.string.memdbg_unavailable);
								}
								refreshTargetValues();
							});
							break;
						case 2:
							prompt(R.string.memdbg_rename, w.name(), text -> {
								dbg.renameWatch(w, text);
							});
							break;
						case 3:
							chooseType(w);
							break;
						case 4:
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
				getString(R.string.memdbg_open_in_memory),
				getString(R.string.memdbg_edit_value),
				getString(f.isEnabled() ? R.string.memdbg_disable : R.string.memdbg_enable),
				getString(R.string.memdbg_rename), getString(R.string.memdbg_remove)};
		new AlertDialog.Builder(requireContext())
				.setTitle(f.name())
				.setItems(items, (d, which) -> {
					switch (which) {
						case 0:
							openInViewer(f);
							break;
						case 1:
							editTargetValue(f, f.value(), v -> dbg.setFreezeValue(f, v));
							break;
						case 2:
							dbg.setFreezeEnabled(f, !f.isEnabled());
							break;
						case 3:
							prompt(R.string.memdbg_rename, f.name(), text -> dbg.renameFreeze(f, text));
							break;
						default:
							dbg.removeFreeze(f);
					}
				})
				.show();
	}

}
