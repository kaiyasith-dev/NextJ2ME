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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/** Asks the system file picker where to create the backup file; the input is the suggested name. */
public class CreateBackupContract extends ActivityResultContract<String, Uri> {
	@RequiresApi(api = Build.VERSION_CODES.KITKAT)
	@NonNull
	@Override
	public Intent createIntent(@NonNull Context context, String name) {
		Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		i.addCategory(Intent.CATEGORY_OPENABLE);
		i.setType("application/zip");
		i.putExtra(Intent.EXTRA_TITLE, name);
		return i;
	}

	@Override
	public Uri parseResult(int resultCode, @Nullable Intent intent) {
		if (resultCode == Activity.RESULT_OK && intent != null) {
			return intent.getData();
		}
		return null;
	}
}
