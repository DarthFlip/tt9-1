package io.github.sspanak.tt9.db.sqlite;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.sspanak.tt9.BuildConfig;
import io.github.sspanak.tt9.util.Logger;

abstract public class SQLiteOpener extends SQLiteOpenHelper {
	private static final String LOG_TAG = SQLiteOpener.class.getSimpleName();

	protected SQLiteDatabase db;


	public SQLiteOpener(@Nullable Context context, @NonNull String name) {
		super(context, name, null, BuildConfig.VERSION_CODE);
	}


	@NonNull abstract protected String[] getCreateQueries();
	@NonNull abstract protected Migration[] getMigrations();


	@Override
	public void onCreate(SQLiteDatabase db) {
		for (String query : getCreateQueries()) {
			db.execSQL(query);
		}
	}


	@Override
	public void onConfigure(SQLiteDatabase db) {
		super.onConfigure(db);
		setWriteAheadLoggingEnabled(true);
	}


	/**
	 * The "schema version" here is BuildConfig.VERSION_CODE, which version-tools.gradle derives
	 * from `git rev-list --count HEAD`. That counter is per-branch, not monotonic across builds:
	 * a later release built from a shorter branch gets a LOWER version code than one already
	 * installed. SQLiteOpenHelper's default reaction is to throw SQLiteDowngradeException, which
	 * surfaced in the field as "Failed loading dictionary: ... Can't downgrade database from
	 * version 1700 to 1665" — every dictionary load failed and the keyboard had no words at all.
	 *
	 * A lower version code does not imply an older schema, so there is nothing to roll back and
	 * nothing to wipe: user data (custom words, learned frequencies) must survive. Ensuring the
	 * tables exist is enough — every create query is CREATE ... IF NOT EXISTS. Migrations are
	 * deliberately not re-run; the database is already at or ahead of what this build expects.
	 */
	@Override
	public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		Logger.w(LOG_TAG, "Database version went down from " + oldVersion + " to " + newVersion + ". Keeping the existing data as-is.");
		onCreate(db);
	}


	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		onCreate(db);
		for (Migration migration : getMigrations()) {
			if (oldVersion > migration.oldVersion()) {
				Logger.d(LOG_TAG, "Skipping migration: '" + migration.query() + "'. Highest previous version: " + migration.oldVersion() + " but we are at: " + oldVersion);
				continue;
			}

			try {
				db.execSQL(migration.query());
				Logger.d(LOG_TAG, "Migration succeeded: '" + migration.query());
			} catch (Exception e) {
				Logger.e(LOG_TAG, "Ignoring migration: '" + migration.query() + "'. ");
			}
		}
	}


	public SQLiteDatabase getDb() {
		if (db == null) {
			db = getWritableDatabase();
		}
		return db;
	}


	public void beginTransaction() {
		if (db != null) {
			db.beginTransactionNonExclusive();
		}
	}


	public void failTransaction() {
		if (db == null) {
			return;
		}

		if (db.inTransaction()) {
			db.endTransaction();
		} else {
			Logger.e(LOG_TAG, "Cannot fail a transaction when not in transaction.");
		}
	}


	public void finishTransaction() {
		if (db == null) {
			return;
		}

		if (db.inTransaction()) {
			db.setTransactionSuccessful();
			db.endTransaction();
		} else {
			Logger.e(LOG_TAG, "Cannot finish a transaction when not in transaction.");
		}
	}
}
