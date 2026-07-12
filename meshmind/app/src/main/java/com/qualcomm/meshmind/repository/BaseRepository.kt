package com.qualcomm.meshmind.repository

import com.qualcomm.meshmind.exceptions.MeshMindException
import com.qualcomm.meshmind.logging.MeshLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Base data repository layer class in Kotlin.
 * Exposes coroutine dispatchers and transaction wrappers.
 */
abstract class BaseRepository {

    protected val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    protected val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    protected val tag: String = javaClass.simpleName

    /**
     * Helper to execute Room queries safely on the I/O thread.
     */
    protected suspend fun <T> executeIO(block: suspend () -> T): T {
        return withContext(ioDispatcher) {
            try {
                block()
            } catch (e: Exception) {
                MeshLogger.e(tag, "Database I/O execution error: ${e.message}", e)
                throw translateException(e)
            }
        }
    }

    private fun translateException(e: Exception): Exception {
        return when (e) {
            is android.database.sqlite.SQLiteException -> {
                MeshMindException.DatabaseException("Underlying SQLite error occurred", e)
            }
            else -> e
        }
    }
}
