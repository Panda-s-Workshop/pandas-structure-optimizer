package com.panda.structopt.fabric;

import com.panda.structopt.platform.StructOptPlatform;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;

/** {@link StructOptPlatform} on Fabric. */
public final class FabricPlatform implements StructOptPlatform {
    @Override
    public String loaderName() {
        return "fabric";
    }

    @Override
    public String modName(String namespace) {
        return FabricLoader.getInstance().getModContainer(namespace).map(c -> c.getMetadata().getName()).orElse(namespace);
    }

    @Override
    public Map<String, String> modVersions() {
        Map<String, String> out = new TreeMap<>();
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            out.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
        }
        return out;
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
