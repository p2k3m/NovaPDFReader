package com.novapdf.reader.integration.aws

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AwsCredentialsModule {

    @Binds
    @Singleton
    abstract fun bindAwsCredentialsProvider(
        impl: BuildConfigAwsCredentialsProvider,
    ): AwsCredentialsProvider
}
