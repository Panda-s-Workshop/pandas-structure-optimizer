package com.panda.structopt.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CacheKeyTest {
    @Test
    void sha256MatchesTheStandardTestVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CacheKey.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void entryChangesWithTheIdAndWithTheFile() {
        String a = CacheKey.entry("minecraft:village/plains/house_1", "00ff");
        assertEquals(a, CacheKey.entry("minecraft:village/plains/house_1", "00ff"));
        assertNotEquals(a, CacheKey.entry("minecraft:village/plains/house_2", "00ff"));
        assertNotEquals(a, CacheKey.entry("minecraft:village/plains/house_1", "00fe"));
        assertEquals(64, a.length());
    }

    @Test
    void entryPathFansOutByTheFirstTwoCharacters() {
        String key = CacheKey.entry("a:b", "c");
        assertEquals(key.substring(0, 2) + "/" + key + ".nbt", CacheKey.entryPath(key));
    }

    @Test
    void fingerprintFollowsTheModListButNotItsOrder() {
        Map<String, String> mods = new LinkedHashMap<>();
        mods.put("railways", "1.0");
        mods.put("c2me", "0.4");
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("c2me", "0.4");
        reversed.put("railways", "1.0");
        String f = CacheKey.fingerprint(4900, mods);
        assertEquals(16, f.length());
        assertEquals(f, CacheKey.fingerprint(4900, reversed));
        assertNotEquals(f, CacheKey.fingerprint(4901, mods));
        Map<String, String> updated = new LinkedHashMap<>(mods);
        updated.put("railways", "1.1");
        assertNotEquals(f, CacheKey.fingerprint(4900, updated));
        assertTrue(CacheKey.fingerprintText(4900, mods).contains("railways 1.0"));
    }

    @Test
    void generatedFabricIdsDoNotChangeTheFingerprint() {
        Map<String, String> a = new LinkedHashMap<>(Map.of("c2me", "0.4", "generated_1b5761d", "1.0.0"));
        Map<String, String> b = new LinkedHashMap<>(Map.of("c2me", "0.4", "generated_c4bb534", "1.0.0"));
        assertEquals(CacheKey.fingerprint(4900, a), CacheKey.fingerprint(4900, b));
    }
}
