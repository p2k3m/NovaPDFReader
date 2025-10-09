package com.novapdf.reader.domain.usecase.di

import com.novapdf.reader.domain.usecase.AdaptiveFlowUseCase
import com.novapdf.reader.domain.usecase.AnnotationUseCase
import com.novapdf.reader.domain.usecase.BookmarkUseCase
import com.novapdf.reader.domain.usecase.CrashReportingUseCase
import com.novapdf.reader.domain.usecase.DefaultAdaptiveFlowUseCase
import com.novapdf.reader.domain.usecase.DefaultAnnotationUseCase
import com.novapdf.reader.domain.usecase.DefaultBookmarkUseCase
import com.novapdf.reader.domain.usecase.DefaultCrashReportingUseCase
import com.novapdf.reader.domain.usecase.DefaultDocumentMaintenanceUseCase
import com.novapdf.reader.domain.usecase.DefaultDocumentSearchUseCase
import com.novapdf.reader.domain.usecase.DefaultPdfDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultPdfViewerUseCases
import com.novapdf.reader.domain.usecase.DefaultRemoteDocumentUseCase
import com.novapdf.reader.domain.usecase.DocumentMaintenanceUseCase
import com.novapdf.reader.domain.usecase.DocumentSearchUseCase
import com.novapdf.reader.domain.usecase.PdfDocumentUseCase
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.domain.usecase.RemoteDocumentUseCase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainUseCaseModule {

    @Binds
    @Singleton
    abstract fun bindPdfViewerUseCases(impl: DefaultPdfViewerUseCases): PdfViewerUseCases

    @Binds
    @Singleton
    abstract fun bindPdfDocumentUseCase(impl: DefaultPdfDocumentUseCase): PdfDocumentUseCase

    @Binds
    @Singleton
    abstract fun bindAnnotationUseCase(impl: DefaultAnnotationUseCase): AnnotationUseCase

    @Binds
    @Singleton
    abstract fun bindBookmarkUseCase(impl: DefaultBookmarkUseCase): BookmarkUseCase

    @Binds
    @Singleton
    abstract fun bindDocumentSearchUseCase(impl: DefaultDocumentSearchUseCase): DocumentSearchUseCase

    @Binds
    @Singleton
    abstract fun bindRemoteDocumentUseCase(impl: DefaultRemoteDocumentUseCase): RemoteDocumentUseCase

    @Binds
    @Singleton
    abstract fun bindDocumentMaintenanceUseCase(
        impl: DefaultDocumentMaintenanceUseCase,
    ): DocumentMaintenanceUseCase

    @Binds
    @Singleton
    abstract fun bindCrashReportingUseCase(impl: DefaultCrashReportingUseCase): CrashReportingUseCase

    @Binds
    @Singleton
    abstract fun bindAdaptiveFlowUseCase(impl: DefaultAdaptiveFlowUseCase): AdaptiveFlowUseCase
}
