package com.novapdf.reader.data.di

import android.content.Context
import androidx.room.Room
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.NovaPdfDatabase
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.work.DocumentMaintenanceScheduler
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
    ): AnnotationRepository = AnnotationRepository(context)

    @Provides
    @Singleton
    fun providePdfDocumentRepository(
        @ApplicationContext context: Context,
        crashReporter: CrashReporter,
    ): PdfDocumentRepository = PdfDocumentRepository(
        context,
        crashReporter = crashReporter,
    )

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
    ): BookmarkManager = BookmarkManager(
        database.bookmarkDao(),
        context.getSharedPreferences(
            BookmarkManager.LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
    )

    @Provides
    @Singleton
    fun provideDocumentMaintenanceScheduler(
        @ApplicationContext context: Context,
    ): DocumentMaintenanceScheduler = DocumentMaintenanceScheduler(context).also {
        it.ensurePeriodicSync()
    }
}
