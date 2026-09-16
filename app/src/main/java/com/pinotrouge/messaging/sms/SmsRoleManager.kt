package com.pinotrouge.messaging.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for ROLE_SMS.
 *
 * Use [RoleManager.isRoleHeld] — not the legacy
 * [android.provider.Telephony.Sms.getDefaultSmsPackage] secure setting, which
 * can disagree with the role and cause silent message loss.
 *
 * Architectural home is `sms/` so onboarding and settings share one API
 * (interface for tests, [roleHeld] Flow for UI that must react to revocation).
 */
interface SmsRoleManager {
    fun isRoleHeld(): Boolean

    /** Alias kept for onboarding call sites. */
    fun isSmsRoleHeld(): Boolean = isRoleHeld()

    fun createRequestRoleIntent(): Intent?

    /** Emits when [refresh] is called after the system role picker. */
    val roleHeld: Flow<Boolean>

    fun refresh()
}

@Singleton
class AndroidSmsRoleManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SmsRoleManager {

    private val _held = MutableStateFlow(isRoleHeld())
    override val roleHeld: Flow<Boolean> = _held.asStateFlow()

    override fun isRoleHeld(): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_SMS)
    }

    override fun createRequestRoleIntent(): Intent? {
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_SMS)) return null
        if (rm.isRoleHeld(RoleManager.ROLE_SMS)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
    }

    override fun refresh() {
        _held.value = isRoleHeld()
    }
}
