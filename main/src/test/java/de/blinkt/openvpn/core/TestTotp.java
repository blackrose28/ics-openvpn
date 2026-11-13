/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import org.junit.Assert;
import org.junit.Test;

/**
 * Checks the TOTP implementation against the RFC 6238 test vectors.
 */
public class TestTotp {

    /* Base32 of the ASCII string "12345678901234567890" */
    private static final String SHA1_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    public void testRfc6238Sha1Vectors() {
        Assert.assertEquals("94287082", Totp.generate(SHA1_SECRET, 59L, 8, 30));
        Assert.assertEquals("07081804", Totp.generate(SHA1_SECRET, 1111111109L, 8, 30));
        Assert.assertEquals("14050471", Totp.generate(SHA1_SECRET, 1111111111L, 8, 30));
        Assert.assertEquals("89005924", Totp.generate(SHA1_SECRET, 1234567890L, 8, 30));
        Assert.assertEquals("69279037", Totp.generate(SHA1_SECRET, 2000000000L, 8, 30));
        Assert.assertEquals("65353130", Totp.generate(SHA1_SECRET, 20000000000L, 8, 30));
    }

    @Test
    public void testSixDigitCode() {
        Assert.assertEquals("287082", Totp.generate(SHA1_SECRET, 59L));
        // Same 30 second step, different second -> same code
        Assert.assertEquals("287082", Totp.generate(SHA1_SECRET, 45L));
        // Next step -> next RFC 4226 counter value
        Assert.assertEquals("359152", Totp.generate(SHA1_SECRET, 89L));
        Assert.assertEquals("081804", Totp.generate(SHA1_SECRET, 1111111109L));
    }

    @Test
    public void testFormattedSecrets() {
        String expected = Totp.generate(SHA1_SECRET, 59L);
        Assert.assertEquals(expected, Totp.generate("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ", 59L));
        Assert.assertEquals(expected, Totp.generate("gezdgnbvgy3tqojqgezdgnbvgy3tqojq", 59L));
        Assert.assertEquals(expected, Totp.generate("GEZD-GNBV-GY3T-QOJQ-GEZD-GNBV-GY3T-QOJQ", 59L));
        Assert.assertEquals(expected, Totp.generate("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ====", 59L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptySecretRejected() {
        Totp.generate("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidSecretRejected() {
        Totp.generate("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJ!");
    }
}
