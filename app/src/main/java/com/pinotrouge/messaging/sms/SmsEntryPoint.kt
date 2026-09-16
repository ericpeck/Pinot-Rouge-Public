package com.pinotrouge.messaging.sms

import com.pinotrouge.messaging.data.telephony.MmsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt access for [BroadcastReceiver]s — `@AndroidEntryPoint` is unreliable
 * on receivers, so we look up dependencies via [dagger.hilt.android.EntryPointAccessors].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmsEntryPoint {
    fun incomingMessagePipeline(): IncomingMessagePipeline
    fun smsSender(): SmsSender
    fun mmsRepository(): MmsRepository
}
