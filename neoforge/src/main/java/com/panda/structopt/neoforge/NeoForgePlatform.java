package com.panda.structopt.neoforge;

import com.panda.structopt.platform.StructOptPlatform;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** {@link StructOptPlatform} on NeoForge. */
public final class NeoForgePlatform implements StructOptPlatform {
    @Override
    public String loaderName() {
        return "neoforge";
    }

    @Override
    public String modName(String namespace) {
        return ModList.get().getModContainerById(namespace).map(c -> c.getModInfo().getDisplayName()).orElse(namespace);
    }

    @Override
    public Map<String, String> modVersions() {
        Map<String, String> out = new TreeMap<>();
        for (var info : ModList.get().getMods()) out.put(info.getModId(), info.getVersion().toString());
        return out;
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }
}
