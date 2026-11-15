package com.clashofmusic.app.data.local

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.io.IOException

interface LibraryVault {
    suspend fun backupCurrentDatabase(): Boolean
    suspend fun <T> reconcile(block: suspend (VaultData?) -> T): T
    suspend fun <T> mutate(block: suspend () -> T): T {
        val result = block()
        backupCurrentDatabase()
        return result
    }
}

/** Snapshot acquisition is inside the lock, so a delayed writer cannot publish a stale snapshot. */
internal class VaultCoordinator(
    private val storage: VaultStorage,
    private val snapshot: suspend () -> VaultData,
    private val canAccessStorage: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) : LibraryVault {
    private val mutex = Mutex()
    private var reconciled = false
    private var lastTimestamp = 0L

    suspend fun load(): VaultData? = mutex.withLock { storage.load() }

    suspend fun exportSnapshot(): VaultData = mutex.withLock {
        check(reconciled && canAccessStorage()) {
            "Sincroniza primero el almacenamiento con los permisos concedidos para exportar un catálogo completo"
        }
        val data = snapshot()
        check(canAccessStorage()) { "Se perdió el acceso al almacenamiento; no se exportó el respaldo" }
        data.copy(updatedAt = nextTimestamp())
    }

    override suspend fun backupCurrentDatabase(): Boolean = mutex.withLock {
        // Never replace a recoverable external catalog with an uninitialized/permission-limited DB.
        if (!reconciled || !canAccessStorage()) return@withLock false
        saveSnapshot()
    }

    override suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        val result = block()
        if (reconciled && canAccessStorage()) {
            try { saveSnapshot() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The DB mutation committed; do not misreport it as a failed download. */ }
        }
        result
    }

    override suspend fun <T> reconcile(block: suspend (VaultData?) -> T): T = mutex.withLock {
        reconciled = false
        val previous = storage.load()
        lastTimestamp = maxOf(lastTimestamp, previous?.updatedAt ?: 0L)
        val result = block(previous)
        if (!canAccessStorage()) throw IOException("No hay acceso al almacenamiento; se conservó el respaldo anterior")
        reconciled = true
        if (!saveSnapshot()) throw IOException("No se pudo actualizar ClashVault; se conservó la copia anterior")
        result
    }

    suspend fun import(data: VaultData): Boolean = mutex.withLock {
        val previous = storage.load()
        lastTimestamp = maxOf(lastTimestamp, previous?.updatedAt ?: 0L)
        val imported = data.copy(updatedAt = nextTimestamp())
        if (storage.save(imported)) {
            reconciled = false
            true
        } else false
    }

    private suspend fun saveSnapshot(): Boolean {
        val data = snapshot()
        if (!canAccessStorage()) return false
        return storage.save(data.copy(updatedAt = nextTimestamp()))
    }
    private fun nextTimestamp(): Long = maxOf(clock(), lastTimestamp + 1L).also { lastTimestamp = it }
}
