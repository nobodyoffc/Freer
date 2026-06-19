package com.fc.fc_ajdk.core.crypto;

public final class CryptoConstants {
    private CryptoConstants() {}

    public static final int KEY_LENGTH_256 = 32;
    public static final int EXTENDED_KEY_LENGTH = 64;
    public static final int IV_LENGTH_CBC = 16;
    public static final int IV_LENGTH_GCM = 12;
    public static final int IV_LENGTH_CHACHA20 = 12;
    public static final int SUM_LENGTH = 4;
    public static final int HMAC_SHA256_LENGTH = 32;
    public static final int ALG_BYTES_LENGTH = 6;
    public static final int KEY_NAME_LENGTH = 6;
    public static final int PUBKEY_COMPRESSED_LENGTH = 33;
    public static final int PUBKEY_X25519_LENGTH = 32;
    public static final int GCM_TAG_LENGTH_BITS = 128;
}
