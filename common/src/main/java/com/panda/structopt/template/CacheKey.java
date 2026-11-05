package com.panda.structopt.template;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Names in the template cache. An entry's key is the template id plus the SHA-256 of the template file's bytes, so a changed
 * file is a new entry. A cache folder is named after a fingerprint of the game's data version and every loaded mod id and
 * version, so any change to the pack (a mod's data fixer included) starts a fresh folder.
 */
public final class CacheKey {
    /** Raise when the entry format changes; old folders are then ignored and cleaned up. */
    public static final int FORMAT = 1;

    private CacheKey() {}

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    /** The entry key: SHA-256 of the id, a line break and the file's hash. */
    public static String entry(String templateId, String fileHash) {
        return sha256((templateId + "\n" + fileHash).getBytes(StandardCharsets.UTF_8));
    }

    /** The entry's file, relative to the fingerprint folder: two fan-out characters, then the key. */
    public static String entryPath(String key) {
        return key.substring(0, 2) + "/" + key + ".nbt";
    }

    /** The text the fingerprint is made from (written next to the folder, so a person can see what it stands for). */
    public static String fingerprintText(int dataVersion, Map<String, String> mods) {
        StringBuilder b = new StringBuilder("format ").append(FORMAT).append("\ndataVersion ").append(dataVersion).append('\n');
        new TreeMap<>(mods).forEach((id, version) -> {
            // Fabric names classpath folders without metadata "generated_<hash>"; the hash follows their contents, not a version
            if (!id.startsWith("generated_")) b.append(id).append(' ').append(version).append('\n');
        });
        return b.toString();
    }

    /** The folder name: the first 16 hex characters of the fingerprint text's SHA-256. */
    public static String fingerprint(int dataVersion, Map<String, String> mods) {
        return sha256(fingerprintText(dataVersion, mods).getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
