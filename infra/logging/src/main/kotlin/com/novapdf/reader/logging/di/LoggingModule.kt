package com.novapdf.reader.logging.di

import android.content.Context
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.logging.FileCrashReporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides
    @Singleton
    fun provideCrashReporter(
        @ApplicationContext context: Context,
    ): CrashReporter = FileCrashReporter(context).also { it.install() }
}
