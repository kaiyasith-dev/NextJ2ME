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

import com.google.gson.annotations.SerializedName;

/** The first entry of every backup file: says what it is and which data layout it holds. */
public final class BackupInfo {
	static final String TYPE = "nextj2me-backup";
	static final int FORMAT = 1;

	@SerializedName("type") String type = TYPE;
	@SerializedName("format") int format = FORMAT;
	/** Layout version of the work folder (see {@code MigrationUtils}). */
	@SerializedName("dataVersion") int dataVersion;
	@SerializedName("created") long created;
	@SerializedName("scope") String scope;
	@SerializedName("appVersion") String appVersion;

	public BackupInfo() {
	}

	public BackupInfo(int dataVersion, BackupScope scope, long created, String appVersion) {
		this.dataVersion = dataVersion;
		this.scope = scope.name();
		this.created = created;
		this.appVersion = appVersion;
	}

	public int dataVersion() {
		return dataVersion;
	}

	public long created() {
		return created;
	}

	public String appVersion() {
		return appVersion;
	}

	/** The scope the backup was made with, or {@link BackupScope#SAVES} if unknown. */
	public BackupScope scope() {
		try {
			return BackupScope.valueOf(scope);
		} catch (RuntimeException e) {
			return BackupScope.SAVES;
		}
	}
}
