package com.trailmix.app.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import android.util.Pair as AndroidPair
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import java.sql.Connection

/**
 * A minimal [SupportSQLiteDatabase] backed by a real JDBC SQLite [Connection], so
 * [MigrationTest] can run TrailMix's actual production [Migration][androidx.room.migration.Migration]
 * objects and get real `ALTER TABLE` behavior — Android's own `android.database.sqlite`
 * classes are unit-test stubs (`isReturnDefaultValues = true`) that never touch real SQL.
 *
 * Every migration in this codebase only ever calls [execSQL] (see `Migrations.kt`), so that is
 * the only member with real behavior; everything else is unused surface required by the
 * interface and throws if a future migration starts relying on it.
 */
class JdbcSupportSQLiteDatabase(private val connection: Connection) : SupportSQLiteDatabase {

    override fun execSQL(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        connection.prepareStatement(sql).use { stmt ->
            bindArgs.forEachIndexed { index, arg -> stmt.setObject(index + 1, arg) }
            stmt.execute()
        }
    }

    private fun unsupported(member: String): Nothing =
        throw UnsupportedOperationException(
            "$member is not implemented by JdbcSupportSQLiteDatabase — TrailMix's migrations " +
                "only ever call execSQL(String); if a new migration needs this, extend this fake.",
        )

    override fun compileStatement(sql: String): SupportSQLiteStatement = unsupported("compileStatement")
    override fun beginTransaction() = unsupported("beginTransaction")
    override fun beginTransactionNonExclusive() = unsupported("beginTransactionNonExclusive")
    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
        unsupported("beginTransactionWithListener")
    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
        unsupported("beginTransactionWithListenerNonExclusive")
    override fun endTransaction() = unsupported("endTransaction")
    override fun setTransactionSuccessful() = unsupported("setTransactionSuccessful")
    override fun inTransaction(): Boolean = false
    override val isDbLockedByCurrentThread: Boolean get() = false
    override fun yieldIfContendedSafely(): Boolean = unsupported("yieldIfContendedSafely")
    override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean =
        unsupported("yieldIfContendedSafely")
    override var version: Int
        get() = unsupported("getVersion")
        set(_) = unsupported("setVersion")
    override val maximumSize: Long get() = unsupported("getMaximumSize")
    override fun setMaximumSize(numBytes: Long): Long = unsupported("setMaximumSize")
    override var pageSize: Long
        get() = unsupported("getPageSize")
        set(_) = unsupported("setPageSize")
    override fun query(query: String): Cursor = unsupported("query")
    override fun query(query: String, bindArgs: Array<out Any?>): Cursor = unsupported("query")
    override fun query(supportSQLiteQuery: SupportSQLiteQuery): Cursor = unsupported("query")
    override fun query(supportSQLiteQuery: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
        unsupported("query")
    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long =
        unsupported("insert")
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int =
        unsupported("delete")
    override fun update(
        table: String,
        conflictAlgorithm: Int,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int = unsupported("update")
    override val isReadOnly: Boolean get() = false
    override val isOpen: Boolean get() = !connection.isClosed
    override fun needUpgrade(newVersion: Int): Boolean = unsupported("needUpgrade")
    override val path: String? get() = null
    override fun setLocale(locale: java.util.Locale) = unsupported("setLocale")
    override fun setMaxSqlCacheSize(cacheSize: Int) = unsupported("setMaxSqlCacheSize")
    override fun setForeignKeyConstraintsEnabled(enabled: Boolean) = Unit
    override fun enableWriteAheadLogging(): Boolean = false
    override fun disableWriteAheadLogging() = Unit
    override val isWriteAheadLoggingEnabled: Boolean get() = false
    override val attachedDbs: List<AndroidPair<String, String>>? get() = null
    override val isDatabaseIntegrityOk: Boolean get() = true
    override fun close() = connection.close()
}
