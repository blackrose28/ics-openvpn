/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal RFC 6238 (TOTP) / RFC 4226 (HOTP) implementation.
 * <p>
 * Used to derive the 6 digit one time password that is prepended to the
 * password of a profile that has an OTP secret configured.
 */
public final class Totp {

    public static final int DEFAULT_DIGITS = 6;
    public static final int DEFAULT_PERIOD = 30;

    private static final int[] POW10 = {1, 10, 100, 1000, 10000, 100000, 1000000,
            10000000, 100000000, 1000000000};

    private Totp() {
    }

    /**
     * Generates the current one time password.
     *
     * @param base32Secret RFC 4648 base32 encoded shared secret
     * @return the OTP as zero padded decimal string
     * @throws IllegalArgumentException if the secret is empty or not valid base32
     */
    public static String generate(String base32Secret) {
        return generate(base32Secret, System.currentTimeMillis() / 1000L);
    }

    /**
     * Generates the one time password valid at the given point in time.
     *
     * @param base32Secret    RFC 4648 base32 encoded shared secret
     * @param unixTimeSeconds seconds since the epoch
     * @return the OTP as zero padded decimal string
     * @throws IllegalArgumentException if the secret is empty or not valid base32
     */
    public static String generate(String base32Secret, long unixTimeSeconds) {
        return generate(base32Secret, unixTimeSeconds, DEFAULT_DIGITS, DEFAULT_PERIOD);
    }

    /**
     * Generates the one time password valid at the given point in time.
     *
     * @param base32Secret    RFC 4648 base32 encoded shared secret
     * @param unixTimeSeconds seconds since the epoch
     * @param digits          number of digits of the resulting code (6..8 is typical)
     * @param period          time step in seconds
     * @return the OTP as zero padded decimal string
     * @throws IllegalArgumentException if the secret is empty, not valid base32
     *                                  or the digits/period are out of range
     */
    public static String generate(String base32Secret, long unixTimeSeconds, int digits, int period) {
        if (digits < 1 || digits >= POW10.length)
            throw new IllegalArgumentException("Invalid number of OTP digits: " + digits);
        if (period < 1)
            throw new IllegalArgumentException("Invalid OTP period: " + period);

        byte[] key = decodeBase32(base32Secret);
        return generate(key, unixTimeSeconds / period, digits);
    }

    private static String generate(byte[] key, long counter, int digits) {
        byte[] hash = hmacSha1(key, counter);

        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int otp = binary % POW10[digits];
        return String.format(Locale.ROOT, "%0" + digits + "d", otp);
    }

    private static byte[] hmacSha1(byte[] key, long counter) {
        byte[] message = ByteBuffer.allocate(8).putLong(counter).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            // HmacSHA1 is mandatory on every Java/Android platform
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }

    /**
     * Decodes an RFC 4648 base32 string. Whitespace, dashes and padding are
     * ignored so secrets can be pasted in the format most services show them.
     * The frequently confused characters 0 and 1 are accepted as O and L.
     *
     * @param base32Secret the secret to decode
     * @return the decoded key bytes
     * @throws IllegalArgumentException if the secret is empty or contains
     *                                  characters that are not valid base32
     */
    public static byte[] decodeBase32(String base32Secret) {
        if (base32Secret == null)
            throw new IllegalArgumentException("OTP secret is empty");

        StringBuilder cleaned = new StringBuilder(base32Secret.length());
        for (int i = 0; i < base32Secret.length(); i++) {
            char c = base32Secret.charAt(i);
            if (c == '=' || Character.isWhitespace(c) || c == '-')
                continue;
            cleaned.append(Character.toUpperCase(c));
        }

        int length = cleaned.length();
        if (length == 0)
            throw new IllegalArgumentException("OTP secret is empty");

        byte[] result = new byte[length * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (int i = 0; i < length; i++) {
            buffer = (buffer << 5) | base32Value(cleaned.charAt(i));
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return result;
    }

    private static int base32Value(char c) {
        if (c >= 'A' && c <= 'Z')
            return c - 'A';
        if (c >= '2' && c <= '7')
            return c - '2' + 26;
        // Some services use these two characters in place of the base32 letters
        if (c == '0')
            return 'O' - 'A';
        if (c == '1')
            return 'L' - 'A';
        throw new IllegalArgumentException("Invalid base32 character in OTP secret: " + c);
    }
}
