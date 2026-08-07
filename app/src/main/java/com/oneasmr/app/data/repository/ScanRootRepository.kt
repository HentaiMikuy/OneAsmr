package com.oneasmr.app.data.repository

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A user-granted SAF root folder: the tree URI from
 * `Intent.ACTION_OPEN_DOCUMENT_TREE` plus the display name resolved at grant
 * time. Stored as JSON in a dedicated DataStore file ("scan_roots") rather
 * than Room, so adding/removing roots never touches the Room schema/migration
 * baseline (Task 4 locked version 1).
 */
@Serializable
data class ScanRoot(
    val displayName: String,
    val treeUri: String,
    val grantedAtEpochMillis: Long,
)

/** Grant status of a stored [ScanRoot] against the CURRENT system state. */
enum class RootGrantStatus {
    /** The tree URI is present in `contentResolver.persistedUriPermissions`. */
    AUTHORIZED,

    /** The user revoked the permission in system settings — entry kept, UI must show it. */
    REVOKED,
}

/** A stored root plus its live grant status (never silently dropped when revoked). */
data class ScanRootEntry(
    val root: ScanRoot,
    val status: RootGrantStatus,
)

enum class AddRootResult {
    OK,
    ALREADY_EXISTS,
    PERMISSION_DENIED,
}

/**
 * Thin wrapper over the system's persistable-URI-permission API so the
 * repository's grant bookkeeping is unit-testable with a fake (the real
 * implementation is Android-only). The [takePersistable] result distinguishes
 * "granted" from "provider refused" (SecurityException — e.g. the picker
 * result lacked the persistable flag).
 */
interface ScanRootPermissionStore {
    /** @return true when the persistable grant was taken successfully. */
    fun takePersistable(treeUri: String): Boolean

    fun releasePersistable(treeUri: String)

    /** Current `contentResolver.persistedUriPermissions` tree URIs. */
    fun persistedTreeUris(): Set<String>
}

/**
 * Resolves a tree URI to a user-visible display name (DocumentsContract
 * query). Android implementation in the Hilt module; tests inject a fake.
 */
interface RootDisplayNameResolver {
    fun resolve(treeUri: String, fallback: String): String
}

/** DataStore-backed persistence for the [ScanRoot] list. */
@Singleton
class ScanRootsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val roots: Flow<List<ScanRoot>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_ROOTS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<ScanRoot>>(raw) }.getOrElse {
            Log.w(TAG, "scan_roots JSON corrupt; treating as empty", it)
            emptyList()
        }
    }

    suspend fun setRoots(newRoots: List<ScanRoot>) {
        dataStore.edit { it[KEY_ROOTS] = json.encodeToString(newRoots) }
    }

    companion object {
        private const val TAG = "OneAsmrScanRoots"
        private val KEY_ROOTS = stringPreferencesKey("roots_json")

        // ignoreUnknownKeys keeps old files readable when new fields land in a
        // future build installed over the previous APK (stale_state class).
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * SAF root-folder management (plan Task 5):
 *
 * - Roots are picked with `Intent.ACTION_OPEN_DOCUMENT_TREE` (the Compose
 *   [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree]
 *   launcher lives in the UI layer) and handed to [addRoot], which takes a
 *   persistable URI permission so the grant survives process death and
 *   reboot.
 * - The stored list is validated against
 *   `contentResolver.persistedUriPermissions` on every [refreshValidation]
 *   (app start, screen resume) and after each mutation. A root whose grant
 *   was revoked in system settings is kept and surfaced as
 *   [RootGrantStatus.REVOKED] — **never silently dropped**.
 * - [removeRoot] both removes the entry and calls
 *   `releasePersistableUriPermission` so the system-level grant is gone too.
 * - No MANAGE_EXTERNAL_STORAGE is (or will ever be) requested; SAF persisted
 *   grants are the entire access mechanism.
 *
 * Every grant/release/validation round is logged under "OneAsmrScanRoots"
 * with the full persisted-URI list — this logcat dump is the acceptance
 * evidence channel (persisted grants are not readable via adb).
 */
@Singleton
class ScanRootRepository @Inject constructor(
    private val permissionStore: ScanRootPermissionStore,
    private val rootsStore: ScanRootsStore,
    private val displayNameResolver: RootDisplayNameResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Snapshot of the system's current persisted tree URIs, kept in sync by mutations + refresh. */
    private val persistedUris = MutableStateFlow(permissionStore.persistedTreeUris())

    val entries: StateFlow<List<ScanRootEntry>> = combine(rootsStore.roots, persistedUris) { roots, persisted ->
        roots.map { root ->
            ScanRootEntry(
                root = root,
                status = if (root.treeUri in persisted) RootGrantStatus.AUTHORIZED else RootGrantStatus.REVOKED,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Adds a picked tree URI: take persistable grant, resolve name, persist. */
    suspend fun addRoot(treeUri: String, fallbackName: String?): AddRootResult {
        val existing = rootsStore.roots.first().any { it.treeUri == treeUri }
        if (existing) {
            Log.i(TAG, "addRoot skipped (already stored): $treeUri")
            return AddRootResult.ALREADY_EXISTS
        }
        if (!permissionStore.takePersistable(treeUri)) {
            Log.w(TAG, "addRoot failed: provider refused persistable grant for $treeUri")
            return AddRootResult.PERMISSION_DENIED
        }
        val name = displayNameResolver.resolve(treeUri, fallbackName ?: treeUri)
        // Publish the new persisted set BEFORE the store write so the combine
        // can never emit a transient REVOKED for a just-granted root.
        persistedUris.value = permissionStore.persistedTreeUris()
        val roots = rootsStore.roots.first() + ScanRoot(
            displayName = name,
            treeUri = treeUri,
            grantedAtEpochMillis = System.currentTimeMillis(),
        )
        rootsStore.setRoots(roots)
        Log.i(TAG, "addRoot granted persistable permission: $treeUri (display='$name')")
        dumpPersistedPermissions("after addRoot($treeUri)")
        return AddRootResult.OK
    }

    /** Removes the root AND releases its system-level persistable permission. */
    suspend fun removeRoot(treeUri: String) {
        rootsStore.setRoots(rootsStore.roots.first().filterNot { it.treeUri == treeUri })
        permissionStore.releasePersistable(treeUri)
        persistedUris.value = permissionStore.persistedTreeUris()
        Log.i(TAG, "removeRoot released persistable permission: $treeUri")
        dumpPersistedPermissions("after removeRoot($treeUri)")
    }

    /**
     * Re-reads `persistedUriPermissions` and reconciles [entries] against it.
     * Called on every screen start so revoked grants surface after process
     * death / system-settings revocation — entries are marked, never dropped.
     */
    suspend fun refreshValidation() {
        persistedUris.value = permissionStore.persistedTreeUris()
        dumpPersistedPermissions("refreshValidation")
    }

    /** Evidence dump: every persisted tree URI currently held by the app. */
    fun dumpPersistedPermissions(reason: String) {
        val uris = permissionStore.persistedTreeUris()
        Log.i(TAG, "persistedUriPermissions dump ($reason) count=${uris.size}: ${uris.sorted()}")
    }

    companion object {
        const val TAG = "OneAsmrScanRoots"
    }
}

/**
 * Android implementation of [ScanRootPermissionStore] over the app's
 * ContentResolver. Requires the picker result to carry
 * `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` (always true for
 * ACTION_OPEN_DOCUMENT_TREE results); [takePersistable] maps the provider's
 * refusal (SecurityException) to `false`.
 */
class AndroidScanRootPermissionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScanRootPermissionStore {
    override fun takePersistable(treeUri: String): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            android.net.Uri.parse(treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    } catch (e: SecurityException) {
        Log.w(ScanRootRepository.TAG, "takePersistableUriPermission refused for $treeUri", e)
        false
    }

    override fun releasePersistable(treeUri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                android.net.Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(ScanRootRepository.TAG, "releasePersistableUriPermission failed for $treeUri", e)
        }
    }

    override fun persistedTreeUris(): Set<String> =
        context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
}

/** Android implementation of [RootDisplayNameResolver] via a DocumentsContract query. */
class AndroidRootDisplayNameResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : RootDisplayNameResolver {
    override fun resolve(treeUri: String, fallback: String): String = try {
        val uri = android.net.Uri.parse(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } ?: fallback else fallback
            } ?: fallback
    } catch (e: Exception) {
        Log.w(ScanRootRepository.TAG, "display-name resolve failed for $treeUri", e)
        fallback
    }
}
