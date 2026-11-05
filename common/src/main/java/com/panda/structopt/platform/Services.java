package com.panda.structopt.platform;

import java.util.ServiceLoader;

/** Finds the loader's implementations (META-INF/services in each loader module). */
public final class Services {
    public static final StructOptPlatform PLATFORM = load(StructOptPlatform.class);

    private Services() {}

    private static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, Services.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + type.getSimpleName() + " for this mod loader"));
    }
}
