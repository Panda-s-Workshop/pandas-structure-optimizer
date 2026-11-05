package com.panda.structopt.platform;

import java.nio.file.Path;
import java.util.Map;

/** What common code needs from the mod loader. Each loader module provides one, found with {@link Services}. */
public interface StructOptPlatform {
    /** "neoforge" or "fabric". */
    String loaderName();

    /** The display name of the mod that owns a namespace, or the namespace itself. */
    String modName(String namespace);

    /** Every loaded mod's id and version. */
    Map<String, String> modVersions();

    /** Whether a mod with this id is loaded. */
    default boolean isLoaded(String modId) {
        return modVersions().containsKey(modId);
    }

    /** The game's config folder. */
    Path configDir();

    /** The game folder (the instance folder on a client, the server folder on a server). */
    Path gameDir();
}
