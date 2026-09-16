package com.pinotrouge.messaging.di

import com.pinotrouge.messaging.rules.DefaultRuleEngine
import com.pinotrouge.messaging.rules.RuleEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SmsModule {

    @Provides
    @Singleton
    fun provideRuleEngine(): RuleEngine = DefaultRuleEngine()
}
