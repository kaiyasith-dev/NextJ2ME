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

package ru.playsoftware.j2meloader;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDex;
import androidx.preference.PreferenceManager;

import org.acra.ACRA;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.config.DialogConfigurationBuilder;


import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.util.Constants;

public class EmulatorApplication extends Application {
	private final SharedPreferences.OnSharedPreferenceChangeListener themeListener = (sharedPreferences, key) -> {
		if (key.equals(Constants.PREF_THEME)) {
			setNightMode(sharedPreferences.getString(Constants.PREF_THEME, null));
		}
	};

	@SuppressWarnings("ConstantConditions")
	@Override
	protected void attachBaseContext(Context base) {
		super.attachBaseContext(base);
		if (BuildConfig.DEBUG) {
			MultiDex.install(this);
		}
		ContextHolder.setApplication(this);
		ACRA.init(this, new CoreConfigurationBuilder()
				.withBuildConfigClass(BuildConfig.class)
				.withParallel(false)
				.withSendReportsInDevMode(true)
				.withPluginConfigurations(new DialogConfigurationBuilder()
						.withTitle(getString(R.string.crash_dialog_title))
						.withText(getString(R.string.crash_dialog_message))
						.withPositiveButtonText(getString(R.string.report_crash))
						.withResTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog)
						.withEnabled(true)
						.build()
				));
		ACRA.getErrorReporter().setEnabled(true);
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
		sp.registerOnSharedPreferenceChangeListener(themeListener);
		setNightMode(sp.getString(Constants.PREF_THEME, null));
		AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
	}

	void setNightMode(String theme) {
		if (theme == null) {
			theme = getString(R.string.pref_theme_default);
		}
		switch (theme) {
			case "light":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
				break;
			case "dark":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
				break;
			case "auto-battery":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
				break;
			case "auto-time":
				//noinspection deprecation
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_TIME);
				break;
			default:
			case "system":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
				break;
		}
	}
}
