package com.novapdf.reader.di

import android.content.Context
import android.util.Log
import com.novapdf.reader.cache.CacheDirectories
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.data.remote.StorageClient
import com.novapdf.reader.data.remote.di.S3Module
import com.novapdf.reader.integration.aws.S3StorageClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dagger.multibindings.IntoSet
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.Dns
import okhttp3.OkHttpClient

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [S3Module::class],
)
object HarnessS3Module {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(DOWNLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(DOWNLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .dns(HarnessAllowlistedDns())
            .build()

    @Provides
    @Singleton
    @IntoSet
    fun provideS3StorageClient(
        s3StorageClient: S3StorageClient,
    ): StorageClient = s3StorageClient

    @Provides
    @Singleton
    fun providePdfDownloadManager(
        @ApplicationContext context: Context,
        storageClient: StorageClient,
        cacheDirectories: CacheDirectories,
    ): PdfDownloadManager = PdfDownloadManager(context, storageClient, cacheDirectories)
}

private class HarnessAllowlistedDns : Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> {
        val normalized = hostname.lowercase(Locale.US).trim().takeIf { it.isNotEmpty() }
            ?: throw UnknownHostException("Hostname is blank")
        if (normalized in ALLOWED_EXACT_HOSTS) {
            return Dns.SYSTEM.lookup(hostname)
        }
        if (ALLOWED_SUFFIXES.any { suffix -> normalized == suffix.trimStart('.') || normalized.endsWith(suffix) }) {
            return Dns.SYSTEM.lookup(hostname)
        }
        Log.w(TAG, "Blocked harness DNS lookup for $hostname")
        throw UnknownHostException("Network access blocked by harness DNS: $hostname")
    }

    private companion object {
        private const val TAG = "HarnessDns"
        private val ALLOWED_EXACT_HOSTS = setOf(
            "s3.amazonaws.com",
            "localhost",
            "127.0.0.1",
            "::1",
        )
        private val ALLOWED_SUFFIXES = setOf(
            ".s3.amazonaws.com",
        )
    }
}

private const val DOWNLOAD_CALL_TIMEOUT_SECONDS = 10L
