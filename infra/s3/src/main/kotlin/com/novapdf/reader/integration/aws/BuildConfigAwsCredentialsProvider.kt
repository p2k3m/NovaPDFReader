package com.novapdf.reader.integration.aws

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class BuildConfigAwsCredentialsProvider @Inject constructor() : AwsCredentialsProvider {

    override fun credentials(): AwsCredentials {
        val accessKeyId = BuildConfig.AWS_ACCESS_KEY_ID.trim()
        val secretAccessKey = BuildConfig.AWS_SECRET_ACCESS_KEY.trim()
        val region = BuildConfig.AWS_REGION.trim().ifEmpty {
            throw IllegalStateException("AWS region is blank; set AWS_REGION or AWS_DEFAULT_REGION during the build.")
        }
        if (accessKeyId.isEmpty()) {
            throw IllegalStateException("AWS access key is blank; set AWS_ACCESS_KEY_ID during the build.")
        }
        if (secretAccessKey.isEmpty()) {
            throw IllegalStateException("AWS secret key is blank; set AWS_SECRET_ACCESS_KEY during the build.")
        }
        val sessionToken = BuildConfig.AWS_SESSION_TOKEN.trim().ifEmpty { null }
        return AwsCredentials(accessKeyId, secretAccessKey, region, sessionToken)
    }
}
