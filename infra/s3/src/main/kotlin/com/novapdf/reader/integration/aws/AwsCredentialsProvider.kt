package com.novapdf.reader.integration.aws

internal interface AwsCredentialsProvider {
    fun credentials(): AwsCredentials
}
