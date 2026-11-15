package com.linkwright;

import com.linkwright.link.McNames;
import com.linkwright.scan.Dist;
import com.linkwright.scan.ModReader;
import com.linkwright.version.VersionScheme;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything Linkwright needs to know about the loader it runs under. */
public interface LoaderPlatform {
    /** "NeoForge", "Forge", "Fabric" or "Quilt", for logs and the report. */
    String loaderName();

    Path gameDir();

    Dist dist();

    List<Path> modDirs();

    List<ModReader> readers();

    VersionScheme versions();

    /** Minecraft and loader jars, named the way mods see them at runtime. May be empty. */
    List<Path> platformJars();

    /** Mod ids that stand for the game, the loader or Java, whose rules are never relaxed. */
    Set<String> platformIds();

    McNames names();

    /** Rules the user's own loader config already relaxes, by dependent mod id. */
    Map<String, Set<String>> existingOverrides();

    /**
     * Instructions for relaxing these rules with the loader's own config instead of Linkwright,
     * or {@code null} when the loader has no such setting.
     */
    String overridesHint(Map<String, Set<String>> approved);

    /** Makes the loader skip these version rules, as dependency ids by dependent mod id, for this launch only. */
    void relax(Map<String, List<String>> overrides) throws Exception;
}
