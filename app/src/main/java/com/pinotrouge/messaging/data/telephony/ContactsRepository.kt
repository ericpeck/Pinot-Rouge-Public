package com.pinotrouge.messaging.data.telephony

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.annotation.VisibleForTesting
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contact lookups for the filter engine and UI. Cached because
 * [isKnownContact] runs on every incoming message.
 *
 * Failed lookups are not cached: a [SecurityException] from a missing
 * [android.Manifest.permission.READ_CONTACTS], or a null provider cursor,
 * must not poison the process as "not a contact". A [ContentObserver] on
 * [ContactsContract.Contacts] drops the cache when contacts change in this
 * or any other app. The observer is retried after a successful query, because
 * `init` may have run before [android.Manifest.permission.READ_CONTACTS]
 * existed.
 */
@Singleton
class ContactsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class CacheEntry(val known: Boolean, val displayName: String?)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Test seam. When set, replaces the ContentResolver query. Throw to
     * simulate a failed lookup (missing [android.Manifest.permission.READ_CONTACTS]).
     * A successful return is a real answer and is cacheable.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    @Volatile
    var queryOverride: ((String) -> Pair<Boolean, String?>)? = null

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    val queryCount = AtomicInteger(0)

    @Volatile
    private var observerRegistered = false

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            cache.clear()
        }
    }

    init {
        ensureObserverRegistered()
    }

    suspend fun isKnownContact(address: String): Boolean = withContext(Dispatchers.IO) {
        lookup(address).known
    }

    suspend fun resolveDisplayName(address: String): String? = withContext(Dispatchers.IO) {
        lookup(address).displayName
    }

    fun clearCache() {
        cache.clear()
        // Permission grant is the other reason to call this — retry observer
        // registration that failed before READ_CONTACTS existed.
        ensureObserverRegistered()
    }

    private fun ensureObserverRegistered() {
        if (observerRegistered) return
        val ok = runCatching {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                contactsObserver,
            )
        }.isSuccess
        if (ok) observerRegistered = true
    }

    private fun lookup(address: String): CacheEntry {
        val key = normalizeKey(address)
        cache[key]?.let { return it }

        val result = runCatching { queryContact(address) }
        // Query could not run (missing permission, provider crash). Safe
        // default for the caller, but do not remember it — the next lookup
        // after a grant must hit the provider again.
        val entry = result.getOrNull()
            ?: return CacheEntry(known = false, displayName = null)
        cache[key] = entry
        return entry
    }

    private fun queryContact(address: String): CacheEntry {
        queryCount.incrementAndGet()
        queryOverride?.let { override ->
            val (known, name) = override(address)
            return CacheEntry(known = known, displayName = name)
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address),
        )
        // A null cursor is a failed lookup, not "not a contact". Caching it
        // as known=false is the same poison as caching a SecurityException.
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
            ),
            null,
            null,
            null,
        ) ?: error("Contacts provider returned a null cursor")
        val entry = cursor.use { rows ->
            if (rows.moveToFirst()) {
                val nameIdx = rows.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                val name = if (nameIdx >= 0) rows.getString(nameIdx) else null
                CacheEntry(known = true, displayName = name)
            } else {
                CacheEntry(known = false, displayName = null)
            }
        }
        // init may have run before READ_CONTACTS existed. A real answer
        // (hit or confirmed miss) means the permission is usable now.
        ensureObserverRegistered()
        return entry
    }

    private fun normalizeKey(address: String): String {
        val digits = address.filter { it.isDigit() }
        return if (digits.length >= 7) digits.takeLast(10) else address.trim().lowercase()
    }

    companion object {
        /**
         * Drop cached lookups from a Compose callback that has a [Context]
         * but not this singleton. Permission grants do not notify the
         * [ContentObserver], so those call sites must clear explicitly.
         */
        fun clearCachedLookups(context: Context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ContactsRepositoryEntryPoint::class.java,
            ).contactsRepository().clearCache()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ContactsRepositoryEntryPoint {
    fun contactsRepository(): ContactsRepository
}
