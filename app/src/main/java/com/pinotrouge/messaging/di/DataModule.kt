package com.pinotrouge.messaging.di

import android.content.Context
import androidx.room.Room
import com.pinotrouge.messaging.data.room.ArchivedThreadDao
import com.pinotrouge.messaging.data.room.BlockedSenderDao
import com.pinotrouge.messaging.data.room.HeldMediaDao
import com.pinotrouge.messaging.data.room.HeldMessageDao
import com.pinotrouge.messaging.data.room.PinotDatabase
import com.pinotrouge.messaging.data.room.PinotMigrations
import com.pinotrouge.messaging.data.room.RuleDao
import com.pinotrouge.messaging.data.room.RuleStatsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Application-scoped work that must outlive a single ViewModel.
 *
 * Used by deferred batch-delete: the real provider write fires after a 5s
 * undo window, and [androidx.lifecycle.ViewModel.viewModelScope] is cancelled
 * in onCleared() — navigating away would otherwise drop the delete and the
 * messages would reappear. See feat/ui-batch-delete Log note.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PinotDatabase {
        return Room.databaseBuilder(
            context,
            PinotDatabase::class.java,
            PinotDatabase.NAME,
        )
            // Real migration — never fallbackToDestructiveMigration (held messages live here).
            .addMigrations(*PinotMigrations.ALL)
            .build()
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    fun provideRuleDao(db: PinotDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideHeldMessageDao(db: PinotDatabase): HeldMessageDao = db.heldMessageDao()

    @Provides
    fun provideHeldMediaDao(db: PinotDatabase): HeldMediaDao = db.heldMediaDao()

    @Provides
    fun provideBlockedSenderDao(db: PinotDatabase): BlockedSenderDao = db.blockedSenderDao()

    @Provides
    fun provideRuleStatsDao(db: PinotDatabase): RuleStatsDao = db.ruleStatsDao()

    @Provides
    fun provideArchivedThreadDao(db: PinotDatabase): ArchivedThreadDao = db.archivedThreadDao()
}
