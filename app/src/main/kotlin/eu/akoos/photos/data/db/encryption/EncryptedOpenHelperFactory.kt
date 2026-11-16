/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package eu.akoos.photos.data.db.encryption

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import eu.akoos.photos.BuildConfig
import eu.akoos.photos.util.PerfDiagnostics
import eu.akoos.photos.util.SyncDiagnostics
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File

/**
 * Hands Room a helper that decides on its first open which file to use ([DatabaseOpenGate]) and opens it
 * encrypted with SQLCipher, or plaintext with the platform SQLite while the plaintext file has not been moved.
 * Nothing is read or decided while Room builds the database object, which can happen on the main thread.
 */
class EncryptedOpenHelperFactory(context: Context) : SupportSQLiteOpenHelper.Factory {

    private val gate: DatabaseOpenGate by lazy { createGate(context.applicationContext) }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        DeferredOpenHelper(configuration) { gate }

    companion object {
        private const val TAG = "DbEncryption"

        fun createGate(context: Context): DatabaseOpenGate {
            val stateDir = File(context.noBackupFilesDir, "database_key")
            val databasesDir = context.getDatabasePath(DatabaseOpenGate.PLAIN_NAME).parentFile!!
            return DatabaseOpenGate(
                databasesDir = databasesDir,
                stateDir = stateDir,
                setAsideDir = File(context.noBackupFilesDir, "database_set_aside"),
                keys = DatabaseKeyStore(stateDir, AndroidKeystoreWrapper()),
                engine = SqlCipherEngine(File(stateDir, "corrupt"), SqlCipherTuning.forContext(context).cacheBytes),
                versionCode = BuildConfig.VERSION_CODE,
                freeBytes = { dir -> runCatching { dir.mkdirs(); StatFs(dir.path).availableBytes }.getOrNull() },
                clock = System::currentTimeMillis,
                log = { line ->
                    Log.i(TAG, line)
                    SyncDiagnostics.log(line)
                },
                recoveredKey = { dir -> (DatabaseKeyStore(dir, AndroidKeystoreWrapper()).load() as? DatabaseKeyState.Ready)?.key },
            )
        }
    }
}

/** True when a rebuilt, empty database replaced one that could not be read, until the hidden vault has re-filed. */
object DatabaseRebuildMarker {
    private fun file(context: Context) = File(File(context.noBackupFilesDir, "database_key"), DatabaseOpenGate.REBUILT_MARKER)

    fun isPresent(context: Context): Boolean = file(context).exists()

    fun clear(context: Context) {
        file(context).delete()
    }
}

internal class DeferredOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val gateProvider: () -> DatabaseOpenGate,
) : SupportSQLiteOpenHelper {

    @Volatile private var delegate: SupportSQLiteOpenHelper? = null
    private var writeAheadLogging: Boolean? = null

    /**
     * The name Room was built with, whichever file the gate opens: Room's driver refuses to open a helper whose
     * name differs from the one it asks for, and every reader resolves it to the same databases directory.
     */
    override val databaseName: String?
        get() = configuration.name

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        synchronized(this) {
            writeAheadLogging = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
        }
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = helper().writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = helper().readableDatabase

    override fun close() {
        synchronized(this) { delegate?.close() }
    }

    private fun helper(): SupportSQLiteOpenHelper {
        delegate?.let { return it }
        synchronized(this) {
            delegate?.let { return it }
            PerfDiagnostics.mark("dbopen:start", 0)
            val gate = gateProvider()
            val target = gate.prepare()
            val helper = when (target) {
                is OpenTarget.Encrypted -> {
                    SqlCipherLibrary.ensureLoaded()
                    val tuning = SqlCipherTuning.forContext(configuration.context)
                    CipherOpenHelper(configuration, target.name, rawKeySpec(target.key), gate.corruptMarker, tuning)
                }
                is OpenTarget.Plain -> FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                        .name(target.name)
                        .callback(configuration.callback)
                        .noBackupDirectory(configuration.useNoBackupDirectory)
                        .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                        .build(),
                )
            }
            writeAheadLogging?.let { helper.setWriteAheadLoggingEnabled(it) }
            helper.writableDatabase
            gate.opened(target)
            PerfDiagnostics.mark(if (target is OpenTarget.Encrypted) "dbopen:end" else "dbopen-plain:end", 0)
            delegate = helper
            return helper
        }
    }
}

/**
 * SQLCipher's own support helper, with an error handler that marks a corruption instead of acting on it and the
 * connection settings of [SqlCipherTuning] on every connection.
 */
private class CipherOpenHelper(
    configuration: SupportSQLiteOpenHelper.Configuration,
    name: String,
    keySpec: String,
    corruptMarker: File,
    tuning: SqlCipherTuning,
) : SupportSQLiteOpenHelper {

    private val openHelper = object : SQLiteOpenHelper(
        configuration.context,
        name,
        keySpec.toByteArray(Charsets.US_ASCII),
        null,
        configuration.callback.version,
        0,
        MarkingErrorHandler(corruptMarker),
        tuning.hook,
        false,
    ) {
        override fun onConfigure(db: SQLiteDatabase) = configuration.callback.onConfigure(db)
        override fun onCreate(db: SQLiteDatabase) = configuration.callback.onCreate(db)
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            configuration.callback.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            configuration.callback.onDowngrade(db, oldVersion, newVersion)
        override fun onOpen(db: SQLiteDatabase) = configuration.callback.onOpen(db)
    }

    override val databaseName: String? get() = openHelper.databaseName

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = openHelper.setWriteAheadLoggingEnabled(enabled)

    override val writableDatabase: SupportSQLiteDatabase get() = openHelper.writableDatabase

    override val readableDatabase: SupportSQLiteDatabase get() = openHelper.readableDatabase

    override fun close() = openHelper.close()
}
