package com.panda.structopt.template;

import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;

/**
 * While the cache runs vanilla's {@code TemplateSource.readStructure} on a miss, this takes the tag the data fixers returned (a
 * copy) and how long they took. Only that thread's own load is captured.
 */
public final class Capture {
    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    public @Nullable CompoundTag fixed;
    public long micros;

    private Capture() {}

    static Capture begin() {
        Capture c = new Capture();
        CURRENT.set(c);
        return c;
    }

    static void end() {
        CURRENT.remove();
    }

    public static @Nullable Capture current() {
        return CURRENT.get();
    }
}
