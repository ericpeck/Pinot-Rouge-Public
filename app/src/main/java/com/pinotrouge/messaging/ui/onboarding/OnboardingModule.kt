package com.pinotrouge.messaging.ui.onboarding

import com.pinotrouge.messaging.sms.AndroidSmsRoleManager
import com.pinotrouge.messaging.sms.SmsRoleManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingModule {
    @Binds
    @Singleton
    abstract fun bindSmsRoleManager(impl: AndroidSmsRoleManager): SmsRoleManager
}
