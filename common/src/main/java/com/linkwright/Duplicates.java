package com.linkwright;

import com.linkwright.scan.ModEntry;
import com.linkwright.scan.ModJar;
import com.linkwright.scan.ModReader;
import com.linkwright.scan.ModScanner;
import com.linkwright.version.VersionScheme;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Disables the older install of a mod that sits in the mods folder more than once, by renaming it
 * to {@code .jar.disabled}, which no loader reads and launchers show as a disabled mod.
 *
 * <p>"Older" means the copy that arrived in the folder first, so a deliberate downgrade wins over
 * the newer version it replaces. Only a file's creation time records that: downloads, copies and
 * unzipping all set it, while the modified time is often carried over from the source. When the
 * creation times cannot tell the copies apart, both stay and the usual warning explains why.
 */
final class Duplicates {
    static final String SUFFIX = ".disabled";
    /** Copies that arrived closer together than this came in one batch, such as a pack install. */
    static final long MIN_GAP_MILLIS = 60000L;

    /** Service files of loaders that load the jar before Linkwright runs. */
    private static final String[] EARLY_SERVICES = {
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
            "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
            "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator",
            "META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModLocator",
            "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
            "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageProvider",
            "META-INF/services/net.neoforged.neoforgespi.language.IModLanguageLoader",
    };

    /** What a run did, for the log and the report. */
    static final class Outcome {
        final List<String> disabled = new ArrayList<>();
        /** Why a duplicated mod id was left alone, by mod id. */
        final Map<String, String> kept = new HashMap<>();
    }

    private static final Outcome NONE = new Outcome();
    private static Outcome last = NONE;

    private Duplicates() {
    }

    /** The outcome of the last {@link #resolve} in this class loader. */
    static synchronized Outcome last() {
        return last;
    }

    static synchronized Outcome resolve(List<Path> modDirs, List<ModReader> readers, VersionScheme versions) {
        Outcome outcome = new Outcome();
        try {
            resolve(ModScanner.scanTopLevel(modDirs, readers, versions), outcome);
        } catch (Exception e) {
            LwLog.debug("Linkwright could not check the mods folder for duplicates: " + e);
        }
        last = outcome;
        return outcome;
    }

    private static final class Copy {
        final Path path;
        final String name;
        final String version;
        final Set<String> modIds = new LinkedHashSet<>();
        long created;
        long modified;
        boolean early;

        Copy(ModJar jar, String version) {
            this.path = jar.path();
            this.name = jar.name();
            this.version = version;
        }
    }

    static void resolve(ModScanner.ModSet mods, Outcome outcome) throws IOException {
        Map<Path, Copy> copies = new LinkedHashMap<>();
        Map<String, List<Copy>> byId = new LinkedHashMap<>();
        try {
            for (ModEntry entry : mods.topLevel()) {
                ModJar jar = entry.jar();
                Copy copy = copies.get(jar.path());
                if (copy == null) {
                    copy = new Copy(jar, entry.version());
                    BasicFileAttributes attributes = Files.readAttributes(jar.path(), BasicFileAttributes.class);
                    copy.created = attributes.creationTime().toMillis();
                    copy.modified = attributes.lastModifiedTime().toMillis();
                    copy.early = loadedEarly(jar);
                    copies.put(jar.path(), copy);
                }
                copy.modIds.add(entry.modId());
                List<Copy> list = byId.get(entry.modId());
                if (list == null) {
                    list = new ArrayList<>();
                    byId.put(entry.modId(), list);
                }
                if (!list.contains(copy)) {
                    list.add(copy);
                }
            }
        } finally {
            mods.close();
        }

        Map<Copy, Copy> disable = new LinkedHashMap<>();
        Map<Copy, String> idOf = new HashMap<>();
        for (Map.Entry<String, List<Copy>> e : byId.entrySet()) {
            List<Copy> list = e.getValue();
            if (list.size() < 2) {
                continue;
            }
            String why = undecidable(e.getKey(), list);
            if (why != null) {
                outcome.kept.put(e.getKey(), why);
                continue;
            }
            Copy keep = list.get(0);
            for (Copy loser : list.subList(1, list.size())) {
                disable.put(loser, keep);
                idOf.put(loser, e.getKey());
            }
        }
        // A jar kept for one mod but disabled for another would take the first mod with it
        for (Map.Entry<Copy, Copy> e : new ArrayList<>(disable.entrySet())) {
            if (disable.containsKey(e.getValue())) {
                disable.remove(e.getKey());
                outcome.kept.put(idOf.get(e.getKey()), e.getValue().name + " is itself an older copy of another mod");
            }
        }

        for (Map.Entry<Copy, Copy> e : disable.entrySet()) {
            Copy loser = e.getKey();
            Copy keep = e.getValue();
            String modId = idOf.get(loser);
            Path target = loser.path.resolveSibling(loser.name + SUFFIX);
            if (Files.exists(target)) {
                outcome.kept.put(modId, target.getFileName() + " already exists");
                continue;
            }
            try {
                Files.move(loser.path, target);
            } catch (FileSystemException ex) {
                outcome.kept.put(modId, loser.name + " is in use, so it could not be renamed"
                        + (ex.getReason() == null ? "" : " (" + ex.getReason().trim() + ")"));
                continue;
            } catch (IOException | RuntimeException ex) {
                outcome.kept.put(modId, loser.name + " could not be renamed (" + ex + ")");
                continue;
            }
            String message = modId + " was in the mods folder more than once. Kept " + describe(keep)
                    + ", the copy added last, and disabled " + describe(loser) + " by renaming it to "
                    + target.getFileName() + ". Rename it back to undo.";
            outcome.disabled.add(message);
            LwLog.warn("Linkwright: " + message);
        }
    }

    /** Sorts the copies newest first and returns why they cannot be told apart, or {@code null}. */
    private static String undecidable(String modId, List<Copy> list) {
        if ("linkwright".equals(modId)) {
            return "Linkwright never disables itself";
        }
        for (Copy copy : list) {
            // Where the file system keeps no creation time, Java reports the modified time instead
            if (copy.created == copy.modified) {
                return "the file system does not record when " + copy.name + " was added";
            }
        }
        Collections.sort(list, new Comparator<Copy>() {
            @Override
            public int compare(Copy a, Copy b) {
                return Long.compare(b.created, a.created);
            }
        });
        Copy keep = list.get(0);
        if (keep.created - list.get(1).created < MIN_GAP_MILLIS) {
            return "the copies were added within a minute of each other";
        }
        if (keep.created > System.currentTimeMillis() + MIN_GAP_MILLIS) {
            return keep.name + " has a creation time in the future";
        }
        for (Copy loser : list.subList(1, list.size())) {
            if (!keep.modIds.containsAll(loser.modIds)) {
                Set<String> extra = new LinkedHashSet<>(loser.modIds);
                extra.removeAll(keep.modIds);
                return loser.name + " also contains " + extra + ", which " + keep.name + " does not";
            }
            if (loser.early) {
                return loser.name + " is loaded by the loader before Linkwright runs";
            }
        }
        return null;
    }

    private static boolean loadedEarly(ModJar jar) {
        try {
            for (String service : EARLY_SERVICES) {
                if (jar.has(service)) {
                    return true;
                }
            }
            byte[] manifest = jar.read("META-INF/MANIFEST.MF");
            if (manifest != null) {
                Attributes main = new Manifest(new ByteArrayInputStream(manifest)).getMainAttributes();
                return main.getValue("TweakClass") != null || main.getValue("FMLCorePlugin") != null;
            }
        } catch (IOException e) {
            return true;
        }
        return false;
    }

    private static String describe(Copy copy) {
        return copy.name + " (" + copy.version + ", added "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(copy.created)) + ")";
    }
}
