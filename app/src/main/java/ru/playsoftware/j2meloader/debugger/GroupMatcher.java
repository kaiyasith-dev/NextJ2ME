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

import java.util.Arrays;

/**
 * Finds groups of values that occur close together (group scan).
 * <p>
 * The input is, for each searched value, the ascending list of positions where it was found in
 * one region. A group needs one position per value, all distinct, such that:
 * <ul>
 * <li>unordered: every position is at most {@code window} away from the group's anchor (the
 * value with the fewest occurrences);</li>
 * <li>ordered: the positions increase in the order the values were given and consecutive
 * positions are at most {@code window} apart.</li>
 * </ul>
 * All positions of every group found are reported.
 */
final class GroupMatcher {
	/** Growable list of ints. */
	static final class IntList {
		int[] a = new int[8];
		int size;

		void add(int v) {
			if (size == a.length) {
				a = Arrays.copyOf(a, size + (size >> 1) + 8);
			}
			a[size++] = v;
		}

		/** Index of the first element greater than or equal to {@code v}. */
		int lowerBound(int v) {
			int lo = 0;
			int hi = size;
			while (lo < hi) {
				int mid = (lo + hi) >>> 1;
				if (a[mid] < v) {
					lo = mid + 1;
				} else {
					hi = mid;
				}
			}
			return lo;
		}

		/** Sorted copy without duplicates. */
		int[] sortedUnique() {
			int[] copy = Arrays.copyOf(a, size);
			Arrays.sort(copy);
			int n = 0;
			for (int i = 0; i < copy.length; i++) {
				if (i == 0 || copy[i] != copy[i - 1]) {
					copy[n++] = copy[i];
				}
			}
			return Arrays.copyOf(copy, n);
		}
	}

	private GroupMatcher() {
	}

	/** Positions of all groups found; may contain duplicates (use {@link IntList#sortedUnique()}). */
	static IntList match(IntList[] lists, int window, boolean ordered) {
		IntList hits = new IntList();
		for (IntList l : lists) {
			if (l.size == 0) {
				return hits; // a value that does not occur at all: no group possible
			}
		}
		if (ordered) {
			matchOrdered(lists, window, hits);
		} else {
			matchUnordered(lists, window, hits);
		}
		return hits;
	}

	private static void matchOrdered(IntList[] lists, int window, IntList hits) {
		int n = lists.length;
		int[] chosen = new int[n];
		IntList first = lists[0];
		for (int i = 0; i < first.size; i++) {
			int prev = first.a[i];
			chosen[0] = prev;
			boolean ok = true;
			for (int j = 1; j < n && ok; j++) {
				IntList l = lists[j];
				int idx = l.lowerBound(prev + 1);
				if (idx >= l.size || (long) l.a[idx] - prev > window) {
					ok = false;
				} else {
					prev = l.a[idx];
					chosen[j] = prev;
				}
			}
			if (ok) {
				for (int v : chosen) {
					hits.add(v);
				}
			}
		}
	}

	private static void matchUnordered(IntList[] lists, int window, IntList hits) {
		int n = lists.length;
		int anchorList = 0;
		for (int j = 1; j < n; j++) {
			if (lists[j].size < lists[anchorList].size) {
				anchorList = j;
			}
		}
		IntList anchors = lists[anchorList];
		int[] chosen = new int[n];
		for (int i = 0; i < anchors.size; i++) {
			int a = anchors.a[i];
			chosen[0] = a;
			int count = 1;
			boolean ok = true;
			for (int j = 0; j < n && ok; j++) {
				if (j == anchorList) {
					continue;
				}
				int q = nearestUnused(lists[j], a, window, chosen, count);
				if (q == NONE) {
					ok = false;
				} else {
					chosen[count++] = q;
				}
			}
			if (ok) {
				for (int v : chosen) {
					hits.add(v);
				}
			}
		}
	}

	private static final int NONE = Integer.MIN_VALUE;

	/** The position in {@code list} closest to {@code a} (within {@code window}) that is not chosen yet. */
	private static int nearestUnused(IntList list, int a, int window, int[] chosen, int count) {
		int hi = list.lowerBound(a);
		int lo = hi - 1;
		while (true) {
			long dl = lo >= 0 ? (long) a - list.a[lo] : Long.MAX_VALUE;
			long dh = hi < list.size ? (long) list.a[hi] - a : Long.MAX_VALUE;
			if (dl > window && dh > window) {
				return NONE;
			}
			int q;
			if (dh <= dl) {
				q = list.a[hi++];
			} else {
				q = list.a[lo--];
			}
			boolean used = false;
			for (int i = 0; i < count; i++) {
				if (chosen[i] == q) {
					used = true;
					break;
				}
			}
			if (!used) {
				return q;
			}
		}
	}
}
