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

package ru.playsoftware.j2meloader.debugger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point of the memory debugger.
 * <p>
 * The debugger inspects the <em>real state of the running game</em>. J2ME-Loader does not
 * interpret bytecode: games are converted to DEX and run as normal Java objects, so there is no
 * emulated heap with raw addresses. State is exposed through four scopes, each backed by a
 * {@link MemoryProvider}: static fields, object fields, primitive arrays and a virtual raw
 * byte space made from those arrays. Everything user visible uses emulator-level ids and virtual
 * addresses, never host pointers.
 * <p>
 * The instance exists only while the master switch is on ({@link #install}); when it is off
 * nothing here is allocated and the emulator hooks reduce to a null check.
 */
public final class MemoryDebugger {
	/** State of the MIDlet as far as the debugger is concerned. */
	public enum MidletState {CREATED, RUNNING, PAUSED, DESTROYED}

	/** Coarse change notification (lifecycle, lists, sessions, pause); called from any thread. */
	public interface Listener {
		void onChanged();
	}

	/** Callbacks of an asynchronous scan; called from the scan thread. */
	public interface ScanListener {
		void onProgress(long regionsDone, long candidates);

		void onFinished(ScanSession session);

		void onCancelled();

		void onError(String message);
	}

	/** One row of the results list. */
	public static final class ScanResult {
		public final MemoryLocation location;
		/** Value remembered by the last scan step. */
		public final MemoryValue previous;

		ScanResult(MemoryLocation location, MemoryValue previous) {
			this.location = location;
			this.previous = previous;
		}
	}

	/** A region opened in the memory viewer. */
	public static final class ViewTarget {
		public final MemoryRegion region;
		/** Offset of the selected byte inside the region image. */
		public final int offset;
		/** Virtual address of byte 0 (raw regions) or 0 for object/class images. */
		public final long base;
		public final boolean raw;
		public final String title;

		ViewTarget(MemoryRegion region, int offset, long base, boolean raw, String title) {
			this.region = region;
			this.offset = offset;
			this.base = base;
			this.raw = raw;
			this.title = title;
		}

		public int size() {
			return region.imageSize();
		}
	}

	/** Short description of a raw array for the region picker. */
	public static final class RegionInfo {
		public final long id;
		public final long address;
		public final int size;
		public final String label;

		RegionInfo(long id, long address, int size, String label) {
			this.id = id;
			this.address = address;
			this.size = size;
			this.label = label;
		}
	}

	private static final int MAX_SESSIONS = 6;
	private static final long MAX_TOTAL_CANDIDATES = 4_000_000;

	private static volatile MemoryDebugger instance;

	/** The running debugger, or null while the master switch is off. */
	public static MemoryDebugger get() {
		return instance;
	}

	public static synchronized MemoryDebugger install(RootSource roots, DebuggerStore store) {
		if (instance == null) {
			instance = new MemoryDebugger(roots, store);
		}
		return instance;
	}

	public static synchronized void uninstall() {
		MemoryDebugger d = instance;
		instance = null;
		if (d != null) {
			d.shutdown();
		}
	}

	private final RootSource roots;
	private final DebuggerStore store;
	private final ObjectRegistry registry = new ObjectRegistry();
	private final AddressSpace space = new AddressSpace();
	private final VmInspector inspector;
	private final MemoryScanner scanner;
	private final RawMemoryProvider rawProvider;
	private final FreezeEngine freezeEngine;
	private final DebuggerSettings settings;

	private final CopyOnWriteArrayList<MemoryWatch> watches = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<MemoryFreeze> freezes = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private final AtomicLong uidGen = new AtomicLong(1);
	private final AtomicBoolean saveQueued = new AtomicBoolean();

	private final List<ScanSession> sessions = new ArrayList<>();
	private ScanSession activeSession;
	private int sessionSeq;

	private volatile int generation = 1;
	private volatile MidletState state = MidletState.CREATED;
	private volatile boolean destroyed;
	private volatile boolean scanning;
	private volatile CancelToken scanCancel = new CancelToken();
	private volatile String invalidationNote;
	private int midletLoads;
	private long tick;
	/** Held for the duration of one freeze tick; lets pausing wait for a tick that is in flight. */
	private final Object enforceLock = new Object();

	private ExecutorService scanExecutor;
	private ExecutorService ioExecutor;

	public MemoryDebugger(RootSource roots, DebuggerStore store) {
		this.roots = roots;
		this.store = store;
		this.inspector = new VmInspector(roots, registry);
		int max = VmInspector.DEFAULT_MAX_OBJECTS;
		this.rawProvider = new RawMemoryProvider(inspector, space, max);
		this.scanner = new MemoryScanner(rawProvider, new VmObjectProvider(inspector, max),
				new StaticFieldProvider(inspector), new ArrayProvider(inspector, max));
		this.freezeEngine = new FreezeEngine(this);
		DebuggerStore.Loaded loaded = store == null ? new DebuggerStore.Loaded() : store.load();
		this.settings = loaded.settings;
		for (DebuggerStore.TargetSpec s : loaded.watches) {
			watches.add(new MemoryWatch(uidGen.getAndIncrement(), s.name, s.ref, s.type,
					s.bigEndian, s.encoding, s.length));
		}
		for (DebuggerStore.TargetSpec s : loaded.freezes) {
			if (s.value != null) {
				freezes.add(new MemoryFreeze(uidGen.getAndIncrement(), s.name, s.ref, s.type,
						s.bigEndian, s.encoding, s.length, s.value, s.enabled));
			}
		}
		freezeEngine.setPeriod(settings.freezePeriodMs);
		freezeEngine.refresh();
	}

	// ================================================================== listeners / state

	public void addListener(Listener l) {
		listeners.addIfAbsent(l);
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	private void fire() {
		for (Listener l : listeners) {
			try {
				l.onChanged();
			} catch (RuntimeException e) {
				// a broken listener must not affect the debugger
			}
		}
	}

	public DebuggerSettings settings() {
		return settings;
	}

	public int generation() {
		return generation;
	}

	public MidletState midletState() {
		return state;
	}

	public boolean isDestroyed() {
		return destroyed;
	}

	/** Why the previous scan results disappeared, or null. */
	public String invalidationNote() {
		return invalidationNote;
	}

	public String statsText() {
		return state + " · run #" + generation + " · " + registry.size() + " objects · "
				+ inspector.knownClasses().size() + " classes";
	}

	// ================================================================== lifecycle

	/**
	 * Called right before the MIDlet class is loaded. A second call in the same process means
	 * the game restarted; everything that referred to the previous run is invalidated.
	 */
	public void onMidletLoading() {
		if (destroyed) {
			return;
		}
		if (midletLoads++ > 0) {
			invalidateSession("The game was restarted");
		}
		state = MidletState.CREATED;
		fire();
	}

	public void onMidletState(MidletState s) {
		if (destroyed || s == MidletState.DESTROYED) {
			return;
		}
		state = s;
		if (s == MidletState.RUNNING) {
			freezeEngine.kick();
		}
		fire();
	}

	/** Called for every class defined by the game's class loader. Cheap and never throws. */
	public void onClassLoaded(Class<?> c) {
		if (!destroyed) {
			try {
				inspector.registerClass(c);
			} catch (RuntimeException e) {
				// tracking is best effort
			}
		}
	}

	/** The game is going away: release every reference and flush the per-game data. */
	public void onMidletDestroyed() {
		if (destroyed) {
			return;
		}
		destroyed = true;
		state = MidletState.DESTROYED;
		scanCancel.cancel();
		PauseGate.releaseAll();
		freezeEngine.shutdown();
		saveNow();
		synchronized (sessions) {
			sessions.clear();
			activeSession = null;
		}
		registry.clear();
		space.clear();
		inspector.clear();
		fire();
		shutdownExecutors();
	}

	/** Drops everything tied to the current run of the game. */
	private void invalidateSession(String reason) {
		generation++;
		scanCancel.cancel();
		synchronized (sessions) {
			sessions.clear();
			activeSession = null;
		}
		registry.clear();
		space.clear();
		inspector.clear();
		PauseGate.releaseAll();
		for (MemoryWatch w : watches) {
			w.setStatus(MemoryTarget.Status.PENDING);
		}
		for (MemoryFreeze f : freezes) {
			f.setStatus(MemoryTarget.Status.PENDING);
		}
		invalidationNote = reason;
	}

	private void shutdown() {
		onMidletDestroyed();
		shutdownExecutors();
	}

	private synchronized void shutdownExecutors() {
		if (scanExecutor != null) {
			scanExecutor.shutdownNow();
			scanExecutor = null;
		}
		if (ioExecutor != null) {
			ioExecutor.shutdown(); // let a queued save finish
			ioExecutor = null;
		}
		SafeCalls.shutdown();
	}

	private void checkAlive() {
		if (destroyed) {
			throw new IllegalStateException("The game is no longer running");
		}
	}

	private static ThreadFactory daemon(final String name) {
		return new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, name);
				t.setDaemon(true);
				return t;
			}
		};
	}

	private synchronized ExecutorService scanExecutor() {
		if (scanExecutor == null) {
			scanExecutor = Executors.newSingleThreadExecutor(daemon("MemDbgScan"));
		}
		return scanExecutor;
	}

	private synchronized ExecutorService ioExecutor() {
		if (ioExecutor == null) {
			ioExecutor = Executors.newSingleThreadExecutor(daemon("MemDbgIo"));
		}
		return ioExecutor;
	}

	/** Runs background work (reference lookups) off the caller's thread. */
	public void runAsync(Runnable r) {
		if (destroyed) {
			return;
		}
		scanExecutor().execute(r);
	}

	// ================================================================== freeze barrier

	/**
	 * Once the pause gate is closed no new freeze tick starts; this waits for one that began just
	 * before, so nothing is written after the game was paused for a scan.
	 */
	void awaitFreezeTick() {
		synchronized (enforceLock) {
			// intentionally empty: acquiring the lock is the barrier
		}
	}

	// ================================================================== scanning

	public boolean isScanning() {
		return scanning;
	}

	public void cancelScan() {
		scanCancel.cancel();
	}

	public List<ScanSession> sessions() {
		synchronized (sessions) {
			return new ArrayList<>(sessions);
		}
	}

	public ScanSession activeSession() {
		synchronized (sessions) {
			return activeSession;
		}
	}

	public void setActiveSession(ScanSession s) {
		synchronized (sessions) {
			if (sessions.contains(s)) {
				activeSession = s;
			}
		}
		fire();
	}

	/** Discards the active scan ("Reset scan"). */
	public void resetScan() {
		synchronized (sessions) {
			if (activeSession != null) {
				sessions.remove(activeSession);
			}
			activeSession = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
		}
		fire();
	}

	private void addSession(ScanSession s) {
		synchronized (sessions) {
			sessions.add(s);
			activeSession = s;
			enforceSessionLimits();
		}
	}

	/** Forgets the oldest scans when there are too many or they hold too many candidates. */
	private void enforceSessionLimits() {
		synchronized (sessions) {
			while (sessions.size() > MAX_SESSIONS) {
				sessions.remove(0);
			}
			long total = 0;
			for (ScanSession x : sessions) {
				total += x.retainedCount();
			}
			// keep the game's heap safe: forget the oldest scans first, never the active one
			while (total > MAX_TOTAL_CANDIDATES && sessions.size() > 1) {
				ScanSession old = sessions.get(0) == activeSession ? sessions.get(1) : sessions.get(0);
				sessions.remove(old);
				total -= old.retainedCount();
			}
		}
	}

	/**
	 * Goes back to the results of an earlier step of a scan (0 is the first scan). Later steps are
	 * discarded.
	 *
	 * @throws IllegalStateException if a scan is running, the results belong to a previous run of
	 *                               the game, or that step was not kept to save memory
	 */
	public void restoreScanStep(ScanSession s, int index) {
		checkAlive();
		if (scanning) {
			throw new IllegalStateException("A scan is running");
		}
		if (s.generation != generation) {
			throw new IllegalStateException("These results belong to a previous run of the game");
		}
		s.restoreTo(index);
		fire();
	}

	/** Synchronous first scan (see {@link #startNewScan}). */
	public ScanSession runNewScan(ScanParams p, MemoryScanner.Progress progress, CancelToken cancel) {
		checkAlive();
		ScanParams params = p.copy();
		params.validate(true);
		ScanSession session;
		synchronized (sessions) {
			session = new ScanSession(++sessionSeq, generation, params);
		}
		runScan(session, params, true, progress, cancel);
		addSession(session);
		rememberScanSettings(params);
		return session;
	}

	/** Synchronous filter step on the active session (see {@link #startNextScan}). */
	public ScanSession runNextScan(ScanParams p, MemoryScanner.Progress progress, CancelToken cancel) {
		checkAlive();
		ScanSession session = activeSession();
		if (session == null) {
			throw new IllegalStateException("There is no scan to continue. Start a new scan first.");
		}
		if (session.generation != generation) {
			throw new IllegalStateException("These results belong to a previous run of the game");
		}
		runScan(session, p.copy(), false, progress, cancel);
		rememberScanSettings(p);
		enforceSessionLimits();
		return session;
	}

	private void runScan(ScanSession session, ScanParams p, boolean first,
						 MemoryScanner.Progress progress, CancelToken cancel) {
		boolean hold = p.pauseDuringScan;
		if (hold) {
			PauseGate.hold();
			awaitFreezeTick();
		}
		try {
			if (first) {
				scanner.firstScan(session, p, progress, cancel);
			} else {
				scanner.nextScan(session, p, progress, cancel);
			}
		} finally {
			if (hold) {
				PauseGate.release();
			}
		}
		if (session.generation != generation) {
			throw new IllegalStateException("The game restarted during the scan");
		}
	}

	private void rememberScanSettings(ScanParams p) {
		ScanParams s = settings.scan;
		s.scope = p.scope;
		s.type = p.type;
		s.mode = p.mode;
		s.value = p.value;
		s.bigEndian = p.bigEndian;
		s.alignment = p.alignment;
		s.encoding = p.encoding;
		s.pauseDuringScan = p.pauseDuringScan;
		s.group = p.group;
		s.groupWindow = p.groupWindow;
		s.groupOrdered = p.groupOrdered;
		markDirty();
	}

	/** Asynchronous first scan on the debugger's scan thread. */
	public void startNewScan(ScanParams p, ScanListener l) {
		startScan(p, true, l);
	}

	/** Asynchronous filter step on the active session. */
	public void startNextScan(ScanParams p, ScanListener l) {
		startScan(p, false, l);
	}

	private void startScan(final ScanParams p, final boolean first, final ScanListener l) {
		if (destroyed) {
			l.onError("The game is no longer running");
			return;
		}
		final CancelToken cancel;
		synchronized (this) {
			if (scanning) {
				l.onError("A scan is already running");
				return;
			}
			scanning = true;
			cancel = new CancelToken();
			scanCancel = cancel;
		}
		fire();
		scanExecutor().execute(new Runnable() {
			@Override
			public void run() {
				try {
					MemoryScanner.Progress progress = new MemoryScanner.Progress() {
						@Override
						public void onProgress(long regionsDone, long candidates) {
							l.onProgress(regionsDone, candidates);
						}
					};
					ScanSession s = first ? runNewScan(p, progress, cancel) : runNextScan(p, progress, cancel);
					scanning = false;
					l.onFinished(s);
				} catch (CancellationException e) {
					scanning = false;
					l.onCancelled();
				} catch (IllegalArgumentException | IllegalStateException e) {
					scanning = false;
					l.onError(e.getMessage());
				} catch (OutOfMemoryError e) {
					scanning = false;
					l.onError("Out of memory during the scan. Narrow the scope or type, or lower the result limit.");
				} catch (RuntimeException e) {
					scanning = false;
					l.onError("Scan failed: " + e);
				} finally {
					scanning = false;
					fire();
				}
			}
		});
	}

	// ================================================================== results

	/** A page of the active session's results. */
	public List<ScanResult> results(ScanSession s, long offset, int limit) {
		MemorySnapshot snap = s.snapshot();
		if (snap == null) {
			return Collections.emptyList();
		}
		List<MemorySnapshot.Entry> page = snap.page(offset, limit);
		List<ScanResult> out = new ArrayList<>(page.size());
		for (MemorySnapshot.Entry e : page) {
			int len = 0;
			MemoryValue prev;
			switch (s.type) {
				case BYTES: {
					byte[] b = (byte[]) e.obj;
					len = b.length;
					prev = MemoryValue.ofBytes(b);
					break;
				}
				case STRING:
					if (s.scope == ScanScope.RAW) {
						byte[] b = (byte[]) e.obj;
						len = b.length;
						prev = MemoryValue.ofString(s.encoding.decode(b), s.encoding);
					} else {
						prev = MemoryValue.ofString((String) e.obj, s.encoding);
					}
					break;
				default:
					prev = MemoryValue.ofBits(s.type, e.bits);
			}
			out.add(new ScanResult(new MemoryLocation(s.scope, e.regionId, e.slot, s.type,
					s.bigEndian, s.encoding, len), prev));
		}
		return out;
	}

	// ================================================================== read / write

	private MemoryRegion regionOf(MemoryLocation loc) {
		if (destroyed) {
			return null;
		}
		try {
			MemoryRegion r = scanner.provider(loc.scope).resolve(loc.regionId);
			return r != null && r.isAvailable() ? r : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Current value at a location, or null if it is unavailable. Never throws. */
	public MemoryValue read(MemoryLocation loc) {
		MemoryRegion r = regionOf(loc);
		if (r == null) {
			return null;
		}
		try {
			return readFrom(r, loc);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static boolean compatible(ValueType wanted, ValueType slot) {
		return wanted.accepts(slot) || (wanted.isNumeric() && slot.isNumeric() && wanted.width() == slot.width());
	}

	private MemoryValue readFrom(MemoryRegion r, MemoryLocation loc) {
		ValueType t = loc.type;
		int slot = loc.slot;
		if (!r.isByteAddressable()) {
			if (slot < 0 || slot >= r.slotCount()) {
				return null;
			}
			ValueType st = r.slotType(slot);
			if (t == ValueType.STRING) {
				return st == ValueType.STRING ? MemoryValue.ofString(r.readString(slot), loc.encoding) : null;
			}
			if (t == ValueType.BYTES || st == ValueType.STRING || !compatible(t, st)) {
				return null;
			}
			return MemoryValue.ofBits(t, r.readRaw(slot, t, loc.bigEndian));
		}
		switch (t) {
			case BYTES:
				return MemoryValue.ofBytes(r.readImage(slot, loc.length));
			case STRING:
				return MemoryValue.ofString(loc.encoding.decode(r.readImage(slot, loc.length)), loc.encoding);
			default:
				return MemoryValue.ofBits(t, r.readRaw(slot, t, loc.bigEndian));
		}
	}

	/**
	 * Writes a value and returns what is stored afterwards (the read-back).
	 *
	 * @throws UnavailableException if the location cannot be reached
	 * @throws IllegalArgumentException if the value does not match the location's type
	 */
	public MemoryValue write(MemoryLocation loc, MemoryValue v) {
		if (v.type != loc.type) {
			throw new IllegalArgumentException("Expected a " + loc.type.label() + " value");
		}
		MemoryRegion r = regionOf(loc);
		if (r == null) {
			throw new UnavailableException("Unavailable");
		}
		try {
			writeTo(r, loc, v);
		} catch (UnavailableException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new UnavailableException("Unavailable", e);
		}
		MemoryValue back = read(loc);
		if (back == null) {
			throw new UnavailableException("Unavailable");
		}
		return back;
	}

	private void writeTo(MemoryRegion r, MemoryLocation loc, MemoryValue v) {
		ValueType t = loc.type;
		int slot = loc.slot;
		if (!r.isByteAddressable()) {
			if (slot < 0 || slot >= r.slotCount()) {
				throw new UnavailableException("No such slot");
			}
			ValueType st = r.slotType(slot);
			if (t == ValueType.STRING) {
				if (st != ValueType.STRING) {
					throw new UnavailableException("Not a String field");
				}
				r.writeString(slot, v.text);
				return;
			}
			if (t == ValueType.BYTES || st == ValueType.STRING || !compatible(t, st)) {
				throw new IllegalArgumentException("Type " + t.label() + " does not fit " + st.label());
			}
			r.writeRaw(slot, t, loc.bigEndian, v.bits);
			return;
		}
		switch (t) {
			case BYTES:
			case STRING: {
				byte[] b = v.rawBytes();
				if (b == null) {
					throw new IllegalArgumentException("Nothing to write");
				}
				r.writeImage(slot, b, 0, b.length);
				break;
			}
			default:
				r.writeRaw(slot, t, loc.bigEndian, v.bits);
		}
	}

	/** Human readable name of a location, e.g. {@code com.example.Game.health}; "Unavailable" if gone. */
	public String describe(MemoryLocation loc) {
		MemoryRegion r = regionOf(loc);
		if (r == null) {
			return "Unavailable";
		}
		try {
			switch (loc.scope) {
				case STATIC_FIELDS:
					return r.typeName() + "." + r.slotName(loc.slot);
				case OBJECTS:
					return simpleName(r.typeName()) + " " + idText(loc.regionId) + "." + r.slotName(loc.slot);
				case ARRAYS:
					return r.label() + " " + idText(loc.regionId) + r.slotName(loc.slot);
				default:
					return AddressSpace.format(space.baseFor(loc.regionId, r.imageSize()) + loc.slot);
			}
		} catch (RuntimeException e) {
			return "Unavailable";
		}
	}

	/** Extra detail line: scope, type and (for raw) the owning array. */
	public String describeDetail(MemoryLocation loc) {
		StringBuilder sb = new StringBuilder();
		sb.append(loc.scope.label()).append(" · ").append(loc.type.label());
		MemoryRegion r = regionOf(loc);
		if (r != null && loc.scope == ScanScope.RAW) {
			sb.append(" · in ").append(r.label()).append(' ').append(idText(loc.regionId));
		}
		return sb.toString();
	}

	public static String idText(long id) {
		return String.format("#0x%08X", id);
	}

	private static String simpleName(String cls) {
		int i = cls.lastIndexOf('.');
		return i < 0 ? cls : cls.substring(i + 1);
	}

	// ================================================================== references

	/**
	 * Builds the most durable reference for a location: a static field or a path from a static
	 * root when one exists, otherwise a session-only reference. Walks the object graph, so call it
	 * from a background thread ({@link #runAsync}).
	 */
	public MemoryReference referenceFor(MemoryLocation loc) {
		MemoryRegion r = regionOf(loc);
		if (r == null) {
			throw new UnavailableException("Unavailable");
		}
		if (loc.scope == ScanScope.STATIC_FIELDS) {
			return MemoryReference.staticField(r.typeName(), r.slotName(loc.slot));
		}
		MemoryReference.Step last = null;
		if (loc.scope == ScanScope.OBJECTS) {
			if (r instanceof FieldRegion) {
				last = MemoryReference.Step.field(((FieldRegion) r).slotOwner(loc.slot), r.slotName(loc.slot));
			}
		} else if (r instanceof ArrayRegion) {
			ArrayRegion ar = (ArrayRegion) r;
			if (loc.scope == ScanScope.ARRAYS) {
				last = MemoryReference.Step.index(loc.slot);
			} else if (loc.type.isNumeric() && loc.type.accepts(ar.elementType()) && loc.bigEndian
					&& loc.slot % loc.type.width() == 0 && loc.type.width() == Math.max(1, ar.elementType().width())) {
				last = MemoryReference.Step.index(loc.slot / loc.type.width());
			}
		}
		if (last != null) {
			Object obj = registry.get(loc.regionId);
			VmInspector.PathResult pr = null;
			if (obj != null) {
				pr = inspector.findPath(obj, VmInspector.DEFAULT_MAX_OBJECTS, CancelToken.NEVER);
			}
			if (pr != null) {
				MemoryReference.Step[] steps = new MemoryReference.Step[pr.steps.size() + 1];
				for (int i = 0; i < pr.steps.size(); i++) {
					steps[i] = pr.steps.get(i);
				}
				steps[steps.length - 1] = last;
				return pr.rootClass != null ? MemoryReference.path(pr.rootClass, pr.rootName, steps)
						: MemoryReference.namedPath(pr.rootName, steps);
			}
		}
		if (loc.scope == ScanScope.RAW) {
			return MemoryReference.raw(space.baseFor(loc.regionId, r.imageSize()) + loc.slot);
		}
		return MemoryReference.object(loc.scope, loc.regionId, loc.slot);
	}

	/** Re-resolves a reference to a concrete location, or null if it is unavailable right now. */
	public MemoryLocation locate(MemoryReference ref, ValueType type, boolean bigEndian,
								 StringEncoding enc, int length) {
		if (destroyed) {
			return null;
		}
		try {
			switch (ref.kind) {
				case RAW: {
					AddressSpace.Located l = space.locate(ref.id);
					return l == null ? null
							: new MemoryLocation(ScanScope.RAW, l.regionId, l.offset, type, bigEndian, enc, length);
				}
				case OBJECT:
					return new MemoryLocation(ref.scope, ref.id, ref.slot, type, bigEndian, enc, length);
				default: {
					VmInspector.Target t = inspector.resolve(ref);
					if (t == null) {
						return null;
					}
					ScanScope scope = t.region.kind() == MemoryRegion.Kind.STATIC ? ScanScope.STATIC_FIELDS
							: t.region.kind() == MemoryRegion.Kind.OBJECT ? ScanScope.OBJECTS : ScanScope.ARRAYS;
					return new MemoryLocation(scope, t.region.id(), t.slot, type, bigEndian, enc, length);
				}
			}
		} catch (RuntimeException e) {
			return null;
		}
	}

	public MemoryLocation locate(MemoryTarget t) {
		return locate(t.ref(), t.type(), t.bigEndian(), t.encoding(), t.length());
	}

	/** Current value of a target, or null (and status UNAVAILABLE) if it cannot be reached. */
	public MemoryValue read(MemoryTarget t) {
		MemoryLocation loc = locate(t);
		MemoryValue v = loc == null ? null : read(loc);
		t.setStatus(v == null ? MemoryTarget.Status.UNAVAILABLE : MemoryTarget.Status.OK);
		return v;
	}

	/** Writes to a target and returns the read-back. */
	public MemoryValue write(MemoryTarget t, MemoryValue v) {
		MemoryLocation loc = locate(t);
		if (loc == null) {
			t.setStatus(MemoryTarget.Status.UNAVAILABLE);
			throw new UnavailableException("Unavailable");
		}
		MemoryValue back = write(loc, v);
		t.setStatus(MemoryTarget.Status.OK);
		return back;
	}

	// ================================================================== watches

	public List<MemoryWatch> watches() {
		return new ArrayList<>(watches);
	}

	public MemoryWatch addWatch(String name, MemoryReference ref, ValueType type, boolean bigEndian,
								StringEncoding enc, int length) {
		MemoryWatch w = new MemoryWatch(uidGen.getAndIncrement(), displayName(name, ref), ref, type,
				bigEndian, enc, length);
		watches.add(w);
		markDirty();
		fire();
		return w;
	}

	public MemoryWatch addWatch(String name, MemoryLocation loc, MemoryReference ref) {
		return addWatch(name, ref, loc.type, loc.bigEndian, loc.encoding, loc.length);
	}

	public void removeWatch(MemoryWatch w) {
		watches.remove(w);
		markDirty();
		fire();
	}

	public void renameWatch(MemoryWatch w, String name) {
		w.setName(displayName(name, w.ref()));
		markDirty();
		fire();
	}

	public void retypeWatch(MemoryWatch w, ValueType type, int length) {
		w.retype(type, length);
		markDirty();
		fire();
	}

	private static String displayName(String name, MemoryReference ref) {
		return name == null || name.trim().isEmpty() ? ref.describe() : name.trim();
	}

	// ================================================================== freezes

	public List<MemoryFreeze> freezes() {
		return new ArrayList<>(freezes);
	}

	public MemoryFreeze addFreeze(String name, MemoryReference ref, ValueType type, boolean bigEndian,
								  StringEncoding enc, int length, MemoryValue value, boolean enabled) {
		MemoryFreeze f = new MemoryFreeze(uidGen.getAndIncrement(), displayName(name, ref), ref, type,
				bigEndian, enc, length, value, enabled);
		freezes.add(f);
		changedFreezes();
		return f;
	}

	public void removeFreeze(MemoryFreeze f) {
		freezes.remove(f);
		changedFreezes();
	}

	public void setFreezeEnabled(MemoryFreeze f, boolean enabled) {
		f.setEnabled(enabled);
		changedFreezes();
		if (enabled) {
			freezeEngine.kick();
		}
	}

	public void setFreezeValue(MemoryFreeze f, MemoryValue value) {
		f.setValue(value);
		changedFreezes();
	}

	public void renameFreeze(MemoryFreeze f, String name) {
		f.setName(displayName(name, f.ref()));
		markDirty();
		fire();
	}

	/** The freeze entry for a reference, if any. */
	public MemoryFreeze findFreeze(MemoryReference ref) {
		for (MemoryFreeze f : freezes) {
			if (f.ref().equals(ref)) {
				return f;
			}
		}
		return null;
	}

	/** Whether the freeze timer thread currently exists (it only does while something is enforced). */
	boolean isFreezeTimerRunning() {
		return freezeEngine.isRunning();
	}

	public int freezePeriodMs() {
		return settings.freezePeriodMs;
	}

	public void setFreezePeriodMs(int ms) {
		settings.freezePeriodMs = Math.max(FreezeEngine.MIN_PERIOD_MS, Math.min(FreezeEngine.MAX_PERIOD_MS, ms));
		freezeEngine.setPeriod(settings.freezePeriodMs);
		markDirty();
	}

	private void changedFreezes() {
		freezeEngine.refresh();
		markDirty();
		fire();
	}

	// ================================================================== enforcement

	/** Number of enabled freezes, which is what the timer is needed for. */
	int activeFreezeCount() {
		int n = 0;
		for (MemoryFreeze f : freezes) {
			if (f.isEnabled()) {
				n++;
			}
		}
		return n;
	}

	/** One tick of the freeze timer. Skipped while the game is paused. */
	void enforceFrozen() {
		synchronized (enforceLock) {
			if (destroyed || PauseGate.isPaused()) {
				return;
			}
			enforceAll();
		}
	}

	private void enforceAll() {
		long n = ++tick;
		for (MemoryFreeze f : freezes) {
			if (f.isEnabled() && due(f.failures, n)) {
				f.failures = enforce(f, f.value()) ? 0 : f.failures + 1;
			}
		}
	}

	/** Failing entries back off to every 10th tick so unresolved freezes stay cheap. */
	private static boolean due(int failures, long tick) {
		return failures < 5 || tick % 10 == 0;
	}

	private boolean enforce(MemoryTarget t, MemoryValue wanted) {
		MemoryLocation loc = locate(t);
		if (loc == null) {
			t.setStatus(MemoryTarget.Status.UNAVAILABLE);
			return false;
		}
		MemoryValue cur = read(loc);
		if (cur == null) {
			t.setStatus(MemoryTarget.Status.UNAVAILABLE);
			return false;
		}
		try {
			if (!cur.equals(wanted)) {
				write(loc, wanted);
			}
			t.setStatus(MemoryTarget.Status.OK);
			return true;
		} catch (RuntimeException e) {
			t.setStatus(MemoryTarget.Status.UNAVAILABLE);
			return false;
		}
	}

	// ================================================================== memory viewer

	/**
	 * Opens a region for the memory viewer. Accepts a virtual address ({@code 0x1A40}) or an
	 * object / class id written as {@code #2A}.
	 */
	public ViewTarget openView(String text) {
		checkAlive();
		String s = text == null ? "" : text.trim();
		if (s.isEmpty()) {
			throw new IllegalArgumentException("Enter an address (0x1A40) or an object id (#2A)");
		}
		try {
			if (s.startsWith("#")) {
				long id = Long.parseLong(s.substring(1).replace("0x", "").replace("0X", ""), 16);
				return openById(id);
			}
			long addr = AddressSpace.parse(s);
			AddressSpace.Located l = space.locate(addr);
			if (l == null) {
				throw new IllegalArgumentException("Nothing is mapped at " + AddressSpace.format(addr)
						+ ". Run a Raw memory scan or use Regions to see mapped arrays.");
			}
			MemoryRegion r = rawProvider.resolve(l.regionId);
			if (r == null) {
				throw new IllegalArgumentException("Unavailable");
			}
			return new ViewTarget(r, l.offset, space.baseFor(l.regionId, r.imageSize()), true, r.label());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Not a valid address: " + s);
		}
	}

	private ViewTarget openById(long id) {
		Object o = registry.get(id);
		if (o == null) {
			throw new IllegalArgumentException("Unavailable: " + idText(id));
		}
		MemoryRegion r = inspector.regionOf(o, false);
		if (r == null) {
			throw new IllegalArgumentException("Nothing to show for " + idText(id));
		}
		return new ViewTarget(r, 0, 0, false, r.label() + " " + idText(id));
	}

	/** Opens the region that holds a scan result, positioned at its byte. */
	public ViewTarget openView(MemoryLocation loc) {
		checkAlive();
		MemoryRegion r = regionOf(loc);
		if (r == null) {
			throw new IllegalArgumentException("Unavailable");
		}
		int off = Math.max(0, r.imageOffset(loc.slot));
		if (loc.scope == ScanScope.RAW) {
			long base = space.baseFor(loc.regionId, r.imageSize());
			return new ViewTarget(r, off, base, true, r.label());
		}
		return new ViewTarget(r, off, 0, false, r.label() + " " + idText(loc.regionId));
	}

	/** Raw arrays currently mapped in the virtual address space (walks the heap; run in background). */
	public List<RegionInfo> listRawRegions(int max) {
		checkAlive();
		final List<RegionInfo> out = new ArrayList<>();
		final int limit = max;
		rawProvider.enumerate(new VmInspector.RegionSink() {
			@Override
			public boolean accept(MemoryRegion region) {
				out.add(new RegionInfo(region.id(), space.baseFor(region.id(), region.imageSize()),
						region.imageSize(), region.label()));
				return out.size() < limit;
			}
		}, CancelToken.NEVER);
		return out;
	}

	/** Reads bytes for the viewer; null when the region became unavailable. */
	public byte[] readView(ViewTarget v, int offset, int len) {
		try {
			if (!v.region.isAvailable()) {
				return null;
			}
			int n = Math.max(0, Math.min(len, v.region.imageSize() - offset));
			return v.region.readImage(offset, n);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ================================================================== persistence

	private void markDirty() {
		if (store == null || destroyed || !saveQueued.compareAndSet(false, true)) {
			return;
		}
		try {
			ioExecutor().execute(new Runnable() {
				@Override
				public void run() {
					saveQueued.set(false);
					saveNow();
				}
			});
		} catch (RuntimeException e) {
			saveQueued.set(false);
		}
	}

	/** Writes the per-game data immediately. Failures are reported only through the return value. */
	public synchronized boolean saveNow() {
		if (store == null) {
			return false;
		}
		List<DebuggerStore.TargetSpec> w = new ArrayList<>();
		List<DebuggerStore.TargetSpec> f = new ArrayList<>();
		for (MemoryWatch x : watches) {
			w.add(spec(x, null, false));
		}
		for (MemoryFreeze x : freezes) {
			f.add(spec(x, x.value(), x.isEnabled()));
		}
		try {
			store.save(settings, w, f);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static DebuggerStore.TargetSpec spec(MemoryTarget t, MemoryValue value, boolean enabled) {
		DebuggerStore.TargetSpec s = new DebuggerStore.TargetSpec();
		s.name = t.name();
		s.ref = t.ref();
		s.type = t.type();
		s.bigEndian = t.bigEndian();
		s.encoding = t.encoding();
		s.length = t.length();
		s.value = value;
		s.enabled = enabled;
		return s;
	}

	/** Formats the interpretations of the bytes at {@code off} like the memory viewer shows them. */
	public static String interpret(byte[] data, int off, boolean bigEndian) {
		StringBuilder sb = new StringBuilder();
		int avail = data.length - off;
		if (avail >= 1) {
			sb.append("Byte:   ").append(ValueType.INT8.fromBytes(data, off, bigEndian))
					.append("  (u").append(ValueType.UINT8.fromBytes(data, off, bigEndian)).append(")\n");
		}
		if (avail >= 2) {
			sb.append("Short:  ").append(ValueType.INT16.fromBytes(data, off, bigEndian))
					.append("  (u").append(ValueType.UINT16.fromBytes(data, off, bigEndian)).append(")\n");
		}
		if (avail >= 4) {
			sb.append("Int:    ").append(ValueType.INT32.fromBytes(data, off, bigEndian))
					.append("  (u").append(ValueType.UINT32.fromBytes(data, off, bigEndian)).append(")\n");
			sb.append("Float:  ").append(ValueType.FLOAT.format(ValueType.FLOAT.fromBytes(data, off, bigEndian)))
					.append('\n');
		}
		if (avail >= 8) {
			sb.append("Long:   ").append(ValueType.INT64.fromBytes(data, off, bigEndian))
					.append("  (u").append(ValueType.UINT64.format(ValueType.UINT64.fromBytes(data, off, bigEndian)))
					.append(")\n");
			sb.append("Double: ").append(ValueType.DOUBLE.format(ValueType.DOUBLE.fromBytes(data, off, bigEndian)))
					.append('\n');
		}
		return sb.toString().trim();
	}
}
