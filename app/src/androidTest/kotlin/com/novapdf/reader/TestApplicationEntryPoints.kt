package com.novapdf.reader

import android.content.Context
import com.novapdf.reader.engine.AdaptiveFlowManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestApplicationEntryPoints {
    fun adaptiveFlowManager(): AdaptiveFlowManager
}

fun adaptiveFlowManager(context: Context): AdaptiveFlowManager {
    return EntryPointAccessors.fromApplication(context, TestApplicationEntryPoints::class.java)
        .adaptiveFlowManager()
}
