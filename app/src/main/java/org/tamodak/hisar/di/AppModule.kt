package org.tamodak.hisar.di

import android.app.DownloadManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.tamodak.hisar.data.local.HisarDatabase
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dispatcher for disk and network work. Injected rather than hard-coded so tests can replace it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher for CPU-bound work such as filtering. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** A scope that lives as long as the app, for work that must finish even if the screen goes away. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HisarDatabase =
        Room.databaseBuilder(context, HisarDatabase::class.java, "hisar.db").build()

    @Provides fun historyDao(db: HisarDatabase) = db.historyDao()
    @Provides fun bookmarkDao(db: HisarDatabase) = db.bookmarkDao()
    @Provides fun closedTabDao(db: HisarDatabase) = db.closedTabDao()
    @Provides fun offlinePageDao(db: HisarDatabase) = db.offlinePageDao()
    @Provides fun downloadDao(db: HisarDatabase) = db.downloadDao()
    @Provides fun tabDao(db: HisarDatabase) = db.tabDao()

    @Provides
    @Singleton
    fun preferences(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(scope.coroutineContext + io),
        produceFile = { context.preferencesDataStoreFile("settings") },
    )

    @Provides
    fun downloadManager(@ApplicationContext context: Context): DownloadManager =
        context.getSystemService(DownloadManager::class.java)
}
