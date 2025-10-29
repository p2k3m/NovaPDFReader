package com.novapdf.reader

import com.novapdf.reader.network.HarnessDnsAllowlist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessDnsAllowlistTest {

    @Test
    fun allowsRegionalS3Hosts() {
        assertTrue(HarnessDnsAllowlist.isAllowedHost("s3.amazonaws.com"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("bucket.s3.amazonaws.com"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("s3.us-west-2.amazonaws.com"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("bucket.s3.us-west-2.amazonaws.com"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("bucket.s3-accelerate.amazonaws.com"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("bucket.s3.dualstack.us-west-2.amazonaws.com"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("bucket.s3.ap-south-1.amazonaws.com"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("bucket.s3.cn-north-1.amazonaws.com.cn"))
    }

    @Test
    fun allowsLocalHosts() {
        assertTrue(HarnessDnsAllowlist.isAllowedHost("LOCALHOST"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("127.0.0.1"))
        assertTrue(HarnessDnsAllowlist.isAllowedHost("::1"))
    }

    @Test
    fun rejectsNonS3AmazonHosts() {
        assertFalse(HarnessDnsAllowlist.isAllowedHost("bucket.amazonaws.com"))
        assertFalse(HarnessDnsAllowlist.isAllowedHost("bucket.s3evil.amazonaws.com"))
        assertFalse(HarnessDnsAllowlist.isAllowedHost("example.com"))
        assertFalse(HarnessDnsAllowlist.isAllowedHost(""))
    }
}
