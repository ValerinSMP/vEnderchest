package com.valerin.venderchest.crossserver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

public record SlotValue(String itemBase64, String fingerprint) {

    private static final SlotValue EMPTY = fromBytes(new byte[0]);

    public SlotValue {
        if (itemBase64 == null || fingerprint == null) throw new IllegalArgumentException("slot value is incomplete");
    }

    public static SlotValue empty() {
        return EMPTY;
    }

    public static SlotValue fromBytes(byte[] itemBytes) {
        byte[] copy = itemBytes.clone();
        return new SlotValue(Base64.getEncoder().encodeToString(copy), sha256(copy));
    }

    public static SlotValue fromText(String value) {
        return fromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes() {
        byte[] stored = Base64.getDecoder().decode(itemBase64);
        if (!sha256(stored).equals(fingerprint)) {
            throw new IllegalArgumentException("slot fingerprint mismatch");
        }
        return stored;
    }

    public boolean isEmpty() {
        return bytes().length == 0;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
