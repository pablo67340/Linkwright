package com.linkwright.fakefml.classic;

import org.apache.maven.artifact.versioning.VersionRange;

import java.util.ArrayList;
import java.util.List;

/** The shape of Forge 1.17 to 1.21.x: a ModVersion class with a final range field, in an unmodifiable list. */
public final class FMLLoader {
    static ModValidator modValidator;

    public static ModInfo install(String modId, String dependency, String range) throws Exception {
        ModInfo info = new ModInfo(modId, List.of(new ModVersion(dependency, VersionRange.createFromVersionSpec(range), true)));
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

    public static final class ModFile {
        private final List<ModInfo> infos;

        ModFile(List<ModInfo> infos) {
            this.infos = infos;
        }

        public List<ModInfo> getModInfos() {
            return infos;
        }
    }

    public static final class ModInfo {
        private final String modId;
        private final List<? extends ModVersion> dependencies;

        ModInfo(String modId, List<? extends ModVersion> dependencies) {
            this.modId = modId;
            this.dependencies = dependencies;
        }

        public String getModId() {
            return modId;
        }

        public List<? extends ModVersion> getDependencies() {
            return dependencies;
        }
    }

    public static final class ModVersion {
        private final String modId;
        private final VersionRange versionRange;
        private final boolean mandatory;

        ModVersion(String modId, VersionRange versionRange, boolean mandatory) {
            this.modId = modId;
            this.versionRange = versionRange;
            this.mandatory = mandatory;
        }

        public String getModId() {
            return modId;
        }

        public VersionRange getVersionRange() {
            return versionRange;
        }

        public boolean isMandatory() {
            return mandatory;
        }
    }
}
