package com.novapdf.reader.search.di

import android.content.Context
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.search.LuceneSearchCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {

    @Provides
    @Singleton
    fun provideDocumentSearchCoordinator(
        @ApplicationContext context: Context,
        repository: PdfDocumentRepository,
        dispatchers: CoroutineDispatchers,
    ): DocumentSearchCoordinator = LuceneSearchCoordinator(context, repository, dispatchers)
}
