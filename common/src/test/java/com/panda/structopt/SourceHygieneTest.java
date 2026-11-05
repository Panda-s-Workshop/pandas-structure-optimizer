package com.panda.structopt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Cheap source-level checks: server safety and lang coverage. */
class SourceHygieneTest {
    private static final Path SRC = Path.of("src", "main", "java");
    private static final Path LANG = Path.of("src", "main", "resources", "assets", "pandas_structure_optimizer", "lang", "en_us.json");

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(SRC)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void commonCodeNeverTouchesClientClasses() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : javaFiles()) {
            String norm = p.toString().replace('\\', '/');
            if (norm.contains("/client/")) continue;
            String text = Files.readString(p);
            if (text.contains("net.minecraft.client") || text.contains("com.mojang.blaze3d")) offenders.add(norm);
        }
        assertTrue(offenders.isEmpty(), "client classes referenced outside the client package: " + offenders);
    }

    @Test
    void literalTranslationKeysExistInTheLangFile() throws IOException {
        String lang = Files.readString(LANG);
        Pattern literal = Pattern.compile("translatable\\(\"([a-z0-9_.]+)\"");
        Set<String> missing = new TreeSet<>();
        for (Path p : javaFiles()) {
            Matcher m = literal.matcher(Files.readString(p));
            while (m.find()) {
                String key = m.group(1);
                if (key.endsWith(".")) continue; // a prefix: the rest is computed
                if (!lang.contains("\"" + key + "\"")) missing.add(key + "  (" + p.getFileName() + ")");
            }
        }
        assertTrue(missing.isEmpty(), "lang keys used in code but missing from en_us.json: " + missing);
    }

    /** Every key in {@link Texts#ALL} has its English text. */
    @Test
    void everyKeyInTextsIsInTheLangFile() throws IOException {
        String lang = Files.readString(LANG);
        List<String> missing = Texts.ALL.stream().filter(key -> !lang.contains("\"" + key + "\"")).toList();
        assertTrue(missing.isEmpty(), "keys in Texts.ALL missing from en_us.json: " + missing);
    }

    @Test
    void everyJsonParsesWithLfEndings() throws IOException {
        Path res = Path.of("src", "main", "resources");
        com.google.gson.Gson gson = new com.google.gson.Gson();
        try (Stream<Path> s = Files.walk(res)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json") || x.toString().endsWith(".mcmeta")).toList()) {
                String text = Files.readString(p);
                assertFalse(text.contains("\r"), "CRLF in " + p);
                assertDoesNotThrow(() -> gson.fromJson(text, Object.class), "invalid JSON: " + p);
            }
        }
    }
}
