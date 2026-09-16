package com.pinotrouge.messaging.di

import com.pinotrouge.messaging.data.telephony.MmsTransport
import com.pinotrouge.messaging.data.telephony.PlatformMmsTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MmsTransportModule {
    @Binds
    @Singleton
    abstract fun bindMmsTransport(impl: PlatformMmsTransport): MmsTransport
}
