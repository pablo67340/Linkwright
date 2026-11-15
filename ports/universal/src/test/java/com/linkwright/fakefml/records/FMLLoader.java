package com.linkwright.fakefml.records;

import org.apache.maven.artifact.versioning.VersionRange;

import java.util.ArrayList;
import java.util.List;

/** The shape of Forge 1.21.11 and later: records, with the dependency list in a mutable holder. */
public final class FMLLoader {
    static ModValidator modValidator;

    public static ModInfo install(String modId, String dependency, String range) throws Exception {
        Holder<ModInfo> owner = new Holder<>(null);
        ModVersion rule = new ModVersion(owner, dependency, VersionRange.createFromVersionSpec(range), true);
        ModInfo info = new ModInfo(modId, new Holder<>(List.of(rule)));
        owner.value = info;
        ModValidator validator = new ModValidator();
        validator.candidateMods.add(new ModFile(List.of(info)));
        modValidator = validator;
        return info;
    }

    public static void reset() {
        modValidator = null;
    }

    static final class ModValidator {
        private final List<ModFile> candidateMods = new ArrayList<>();
    }

    public record ModFile(List<ModInfo> getModInfos) {
    }

    static final class Holder<T> {
        private T value;

        Holder(T value) {
            this.value = value;
        }
    }

    public record ModInfo(String getModId, Holder<List<? extends ModVersion>> dependencies) {
        public List<? extends ModVersion> getDependencies() {
            return dependencies.value;
        }
    }

    public record ModVersion(Holder<ModInfo> owner, String getModId, VersionRange getVersionRange, boolean isMandatory) {
    }
}
