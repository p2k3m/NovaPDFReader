package com.novapdf.reader.data.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.NovaPdfDatabase
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.remote.StorageEngine
import com.novapdf.reader.data.UserPreferencesRepository
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import com.novapdf.reader.cache.CacheDirectories
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideAnnotationRepository(
        @ApplicationContext context: Context,
        dispatchers: CoroutineDispatchers,
    ): AnnotationRepository = AnnotationRepository(context, dispatchers = dispatchers)

    @Provides
    @Singleton
    internal fun providePdfDocumentRepository(
        @ApplicationContext context: Context,
        crashReporter: CrashReporter,
        dispatchers: CoroutineDispatchers,
        storageEngine: StorageEngine,
        cacheDirectories: CacheDirectories,
        bitmapCacheFactory: PdfDocumentRepository.BitmapCacheFactory,
        bitmapPoolFactory: PdfDocumentRepository.BitmapPoolFactory,
    ): PdfDocumentRepository {
        val repository = PdfDocumentRepository(
            context,
            ioDispatcher = dispatchers.io,
            crashReporter = crashReporter,
            storageEngine = storageEngine,
            cacheDirectories = cacheDirectories,
            bitmapCacheFactory = bitmapCacheFactory,
            bitmapPoolFactory = bitmapPoolFactory,
        )

        enforceNoCaffeineCaches(repository)

        return repository
    }

    @Provides
    internal fun provideBitmapCacheFactory(): PdfDocumentRepository.BitmapCacheFactory =
        PdfDocumentRepository.defaultBitmapCacheFactory()

    @Provides
    internal fun provideBitmapPoolFactory(): PdfDocumentRepository.BitmapPoolFactory =
        PdfDocumentRepository.defaultBitmapPoolFactory()

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): NovaPdfDatabase = Room.databaseBuilder(
        context,
        NovaPdfDatabase::class.java,
        NovaPdfDatabase.NAME,
    ).fallbackToDestructiveMigration().build()

    @Provides
    @Singleton
    fun provideBookmarkManager(
        database: NovaPdfDatabase,
        @ApplicationContext context: Context,
        dispatchers: CoroutineDispatchers,
    ): BookmarkManager = BookmarkManager(
        database.bookmarkDao(),
        context.getSharedPreferences(
            BookmarkManager.LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
        dispatcher = dispatchers.io,
    )

    @Provides
    @Singleton
    fun provideUserPreferencesRepository(
        @ApplicationContext context: Context,
    ): UserPreferencesRepository = UserPreferencesRepository(context)

    @Provides
    @Singleton
    fun provideDocumentMaintenanceScheduler(
        @ApplicationContext context: Context,
    ): DocumentMaintenanceScheduler = DocumentMaintenanceScheduler(context).also {
        it.ensurePeriodicSync()
    }

    private fun enforceNoCaffeineCaches(repository: PdfDocumentRepository) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        val offendingField = repository::class.java.declaredFields.firstNotNullOfOrNull { field ->
            field.isAccessible = true
            val declaredType = field.type.name
            if (declaredType.contains("caffeine", ignoreCase = true)) {
                field.name to declaredType
            } else {
                val valueType = runCatching { field.get(repository)?.javaClass?.name }.getOrNull()
                if (valueType?.contains("caffeine", ignoreCase = true) == true) {
                    field.name to valueType
                } else {
                    null
                }
            }
        }

        check(offendingField == null) {
            val (fieldName, typeName) = offendingField!!
            "Caffeine caches are not supported on API 28+. Field '$fieldName' is backed by $typeName; replace it with an Android-safe alternative."
        }
    }
}
