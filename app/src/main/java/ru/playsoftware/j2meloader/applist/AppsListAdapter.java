/*
 * Copyright 2015-2016 Nickolay Savchenko
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

package ru.playsoftware.j2meloader.applist;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.databinding.ListRowJarBinding;
import ru.playsoftware.j2meloader.util.AppSizeCache;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.StorageSize;

public class AppsListAdapter extends BaseAdapter implements Filterable {
	private static final long MIN_REFRESH_INTERVAL_MS = 2000;

	private List<AppItem> list = new ArrayList<>();
	private List<AppItem> filteredList = new ArrayList<>();
	private final AppFilter appFilter = new AppFilter();
	private CharSequence filterConstraint;
	/** Size in bytes (game files plus saved data) per app folder; filled in the background. */
	private final Map<String, Long> sizes = new HashMap<>();
	private final AppSizeCache sizeCache = new AppSizeCache();
	private long lastSizeRefresh;
	private final ExecutorService sizeExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "AppSizes");
		t.setDaemon(true);
		return t;
	});
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private int sizeGeneration;

	@Override
	public int getCount() {
		return filteredList.size();
	}

	@Override
	public AppItem getItem(int position) {
		return filteredList.get(position);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public View getView(int position, View view, ViewGroup parent) {
		ViewHolder holder;
		if (view == null) {
			ListRowJarBinding binding = ListRowJarBinding.inflate(
					LayoutInflater.from(parent.getContext()), parent, false);
			view = binding.getRoot();
			holder = new ViewHolder(binding);
			view.setTag(holder);
		} else {
			holder = (ViewHolder) view.getTag();
		}

		AppItem item = filteredList.get(position);
		Drawable icon = Drawable.createFromPath(item.getImagePathExt());
		if (icon != null) {
			icon.setFilterBitmap(false);
			holder.binding.icon.setImageDrawable(icon);
		} else {
			holder.binding.icon.setImageResource(R.mipmap.ic_launcher);
		}
		holder.binding.name.setText(item.getTitle());
		holder.binding.author.setText(item.getAuthor());
		holder.binding.appVersion.setText(item.getVersion());
		Long size = sizes.get(item.getPath());
		holder.binding.appSize.setText(size == null ? "" : parent.getContext()
				.getString(R.string.size_kb, StorageSize.formatKb(size, Locale.getDefault())));

		return view;
	}

	public void setItems(List<AppItem> items) {
		list = items;
		appFilter.filter(filterConstraint);
		refreshSizes();
	}

	/** Like {@link #refreshSizes()}, but skipped if the sizes were refreshed a moment ago. */
	public void refreshSizesIfStale() {
		if (System.currentTimeMillis() - lastSizeRefresh >= MIN_REFRESH_INTERVAL_MS) {
			refreshSizes();
		}
	}

	/**
	 * Updates the sizes in the background (after installs, deletes, "Clear data" and when coming
	 * back to the list). Game files are only read again when their folder changed, so this is
	 * cheap. The previous values stay on screen meanwhile, so the list does not flicker.
	 */
	public void refreshSizes() {
		lastSizeRefresh = System.currentTimeMillis();
		final List<AppItem> snapshot = new ArrayList<>(list);
		final int generation = ++sizeGeneration;
		try {
			sizeExecutor.execute(() -> {
				Map<String, Long> measured = new HashMap<>();
				Set<String> paths = new HashSet<>();
				for (AppItem item : snapshot) {
					if (generation != sizeGeneration) {
						return; // a newer refresh replaced this one
					}
					paths.add(item.getPath());
					measured.put(item.getPath(), sizeCache.totalSize(item.getPath(),
							new File(item.getPathExt()), AppUtils.getDataDir(item)));
				}
				sizeCache.retainOnly(paths);
				mainHandler.post(() -> {
					if (generation == sizeGeneration && !sizes.equals(measured)) {
						// only redraw when something changed
						sizes.clear();
						sizes.putAll(measured);
						notifyDataSetChanged();
					}
				});
			});
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
			// released: the list is gone
		}
	}

	/** Stops the background measuring; call when the list is gone. */
	public void release() {
		sizeGeneration++;
		sizeExecutor.shutdownNow();
	}

	@Override
	public Filter getFilter() {
		return appFilter;
	}

	private static class ViewHolder {
		ListRowJarBinding binding;

		// todo неясно, может быть здесь стоит binding очищать на этапе
		// ondestroy/ondestroyview где используется этот класс
		private ViewHolder(ListRowJarBinding binding) {
			this.binding = binding;
		}
	}

	private class AppFilter extends Filter {

		@Override
		protected FilterResults performFiltering(CharSequence constraint) {
			FilterResults results = new FilterResults();
			if (TextUtils.isEmpty(constraint)) {
				results.count = list.size();
				results.values = list;
			} else {
				ArrayList<AppItem> resultList = new ArrayList<>();
				for (AppItem item : list) {
					if (item.getTitle().toLowerCase().contains(constraint)
							|| item.getAuthor().toLowerCase().contains(constraint)) {
						resultList.add(item);
					}
				}
				results.count = resultList.size();
				results.values = resultList;
			}
			return results;
		}

		@Override
		protected void publishResults(CharSequence constraint, FilterResults results) {
			filterConstraint = constraint;
			if (results.values != null) {
				//noinspection unchecked
				filteredList = (List<AppItem>) results.values;
				notifyDataSetChanged();
			} else {
				notifyDataSetInvalidated();
			}
		}
	}
}
