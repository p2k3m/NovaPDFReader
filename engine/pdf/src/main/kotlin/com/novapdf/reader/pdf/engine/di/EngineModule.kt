package com.novapdf.reader.pdf.engine.di

import android.content.Context
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.pdf.engine.DefaultAdaptiveFlowManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideAdaptiveFlowManager(
        @ApplicationContext context: Context,
    ): AdaptiveFlowManager = DefaultAdaptiveFlowManager(context)
}
