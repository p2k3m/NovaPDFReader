package com.novapdf.reader.integration.aws

internal data class AwsCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val region: String,
    val sessionToken: String?,
)
