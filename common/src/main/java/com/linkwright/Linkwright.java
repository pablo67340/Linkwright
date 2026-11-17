package com.linkwright;

import com.linkwright.link.ClassIndex;
import com.linkwright.link.Hierarchy;
import com.linkwright.link.LinkChecker;
import com.linkwright.link.PairReport;
import com.linkwright.link.Patch;
import com.linkwright.link.Problem;
import com.linkwright.scan.Dependency;
import com.linkwright.scan.Dist;
import com.linkwright.scan.ModEntry;
import com.linkwright.scan.ModJar;
import com.linkwright.scan.ModScanner;
import com.linkwright.version.VersionScheme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds every version rule the installed mods break, on each other or on a loader newer than they
 * allow, verifies each one at the bytecode level, and decides which to approve and which call sites
 * to bridge.
 */
public final class Linkwright {
    public static final String VERSION = Linkwright.class.getPackage() == null
            || Linkwright.class.getPackage().getImplementationVersion() == null
            ? "dev" : Linkwright.class.getPackage().getImplementationVersion();

    public static final class Unmet {
        public final ModEntry dependent;
        public final ModEntry dependency;
        public final Dependency rule;

        Unmet(ModEntry dependent, ModEntry dependency, Dependency rule) {
            this.dependent = dependent;
            this.dependency = dependency;
            this.rule = rule;
        }
    }

    public static final class Result {
        public static final Result EMPTY = new Result(Collections.<String, List<String>>emptyMap(),
                Collections.<Patch>emptyList(), Collections.<PairReport>emptyList());

        /** Rules to relax, as dependency ids by dependent mod id, minus ones the loader config already relaxes. */
        public final Map<String, List<String>> overrides;
        /** Call sites, field accesses and methods to adapt as classes load. */
        public final List<Patch> patches;
        public final List<PairReport> reports;

        Result(Map<String, List<String>> overrides, List<Patch> patches, List<PairReport> reports) {
            this.overrides = Collections.unmodifiableMap(overrides);
            this.patches = Collections.unmodifiableList(patches);
            this.reports = Collections.unmodifiableList(reports);
        }

        /** Every rule to relax as "dependent->dependency" pairs. */
        public Set<String> pairs() {
            Set<String> pairs = new LinkedHashSet<>();
            for (Map.Entry<String, List<String>> e : overrides.entrySet()) {
                for (String dependency : e.getValue()) {
                    pairs.add(e.getKey() + "->" + dependency);
                }
            }
            return pairs;
        }
    }

    /** A result plus every rule that ends up relaxed, including ones the loader config already relaxed. */
    static final class Analysis {
        final Result result;
        final Map<String, Set<String>> approved;
        final int modCount;
        /** Problems with the mods folder that Linkwright can only point out. */
        final List<String> warnings;

        Analysis(Result result, Map<String, Set<String>> approved, int modCount, List<String> warnings) {
            this.result = result;
            this.approved = approved;
            this.modCount = modCount;
            this.warnings = warnings;
        }
    }

    private static final Pattern VERSION_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)*(?:-[0-9A-Za-z.]+)?");

    private Linkwright() {
    }

    public static Result run(LoaderPlatform platform) throws Exception {
        long start = System.nanoTime();
        Path gameDir = platform.gameDir();
        LinkwrightConfig config = LinkwrightConfig.load(gameDir.resolve("config").resolve("linkwright.toml"));
        if (config.mode() == LinkwrightConfig.Mode.OFF) {
            LwLog.info("Linkwright is off (config/linkwright.toml)");
            return Result.EMPTY;
        }
        Analysis analysis = analyze(platform, config);
        ReportWriter.write(gameDir.resolve("logs").resolve("linkwright-report.txt"), config, platform,
                analysis.result.reports, analysis.approved, analysis.warnings);
        for (String warning : analysis.warnings) {
            LwLog.warn("Linkwright: " + warning);
        }
        if (analysis.result.reports.isEmpty()) {
            LwLog.info("Linkwright: all " + analysis.modCount + " mods' dependency versions are satisfied");
        } else {
            logSummary(analysis.result.reports, (System.nanoTime() - start) / 1000000L);
        }
        return analysis.result;
    }

    static Analysis analyze(LoaderPlatform platform, LinkwrightConfig config) throws Exception {
        return analyze(platform, config, Diagnostics.runtimeClassVersion());
    }

    static Analysis analyze(LoaderPlatform platform, LinkwrightConfig config, int runtimeClassVersion) throws Exception {
        Map<String, Set<String>> existing = platform.existingOverrides();
        List<ModJar> platformJars = new ArrayList<>();
        ModJar loaderJar = null;
        ModScanner.ModSet mods = ModScanner.scan(platform.modDirs(), platform.readers(), platform.versions());
        try {
            List<String> warnings = Diagnostics.check(mods, runtimeClassVersion);
            Map<String, ModEntry> all = new LinkedHashMap<>(mods.mods());
            Set<String> skip = new HashSet<>(platform.platformIds());
            LoaderMod loader = loader(platform);
            if (loader != null) {
                loaderJar = ModJar.top(loader.jar);
                for (String id : loader.ids) {
                    if (!all.containsKey(id)) {
                        all.put(id, ModEntry.loader(id, loader.version, loaderJar, loader.packages));
                        skip.remove(id);
                    }
                }
            }
            List<Unmet> unmet = findUnmet(all, platform.dist(), skip, platform.versions());
            if (unmet.isEmpty()) {
                return new Analysis(Result.EMPTY, Collections.<String, Set<String>>emptyMap(), mods.mods().size(), warnings);
            }

            List<Path> platformPaths = platform.platformJars();
            for (Path jar : platformPaths) {
                platformJars.add(ModJar.top(jar));
            }
            List<ModJar> indexed = new ArrayList<>(mods.jars());
            indexed.addAll(platformJars);
            if (loaderJar != null && !platformPaths.contains(loaderJar.path())) {
                indexed.add(loaderJar);
            }
            Hierarchy hierarchy = new Hierarchy(new ClassIndex(indexed), platform.names());
            LinkChecker checker = new LinkChecker(hierarchy);
            List<PairReport> reports = new ArrayList<>();
            Map<String, List<String>> overrides = new LinkedHashMap<>();
            Map<String, Set<String>> approvedAll = new LinkedHashMap<>();
            List<Patch> patches = new ArrayList<>();

            for (Unmet u : unmet) {
                String dependentId = u.dependent.modId();
                String dependencyId = u.dependency.modId();
                String pair = dependentId + "->" + dependencyId;
                Set<String> relaxed = existing.get(dependentId);
                boolean already = relaxed != null && relaxed.contains(dependencyId);
                PairReport report = checker.check(u.dependent, u.dependency, u.rule.rangeSpec(), already);
                reports.add(report);

                if (config.ignore().contains(pair)) {
                    report.decision = PairReport.Decision.IGNORED;
                    continue;
                }
                if (config.mode() == LinkwrightConfig.Mode.REPORT) {
                    report.decision = PairReport.Decision.REPORT_ONLY;
                    continue;
                }
                if (report.clean()) {
                    report.decision = PairReport.Decision.APPROVED;
                } else if (config.forceApprove().contains(pair)) {
                    report.decision = PairReport.Decision.APPROVED_FORCED;
                } else {
                    report.decision = PairReport.Decision.REFUSED;
                }

                boolean approved = report.decision != PairReport.Decision.REFUSED;
                if (approved) {
                    addTo(approvedAll, dependentId, dependencyId);
                    if (!already) {
                        List<String> list = overrides.get(dependentId);
                        if (list == null) {
                            list = new ArrayList<>();
                            overrides.put(dependentId, list);
                        }
                        list.add(dependencyId);
                    }
                }
                // A rule the pack already relaxes loads either way, so bridging whatever can be
                // bridged only removes crashes.
                if (config.bridgeCalls() && (approved || already)) {
                    patches.addAll(report.patches);
                }
            }

            return new Analysis(new Result(overrides, patches, reports), approvedAll, mods.mods().size(), warnings);
        } finally {
            for (ModJar jar : platformJars) {
                jar.close();
            }
            if (loaderJar != null) {
                loaderJar.close();
            }
            mods.close();
        }
    }

    private static LoaderMod loader(LoaderPlatform platform) {
        try {
            LoaderMod loader = platform.loader();
            if (loader != null && loader.version != null && loader.jar != null && Files.isRegularFile(loader.jar)) {
                return loader;
            }
            LwLog.debug("Linkwright could not locate " + platform.loaderName() + "'s jar; its version rules stay as they are");
        } catch (RuntimeException e) {
            LwLog.debug("Linkwright could not read " + platform.loaderName() + "'s version: " + e);
        }
        return null;
    }

    /**
     * Whether {@code version} is newer than every version {@code range} mentions, so the rule only
     * fails because the version is too new. Reading the versions out of the text works the same for
     * Maven ranges ({@code [21.1.243]}) and Fabric predicates ({@code >=0.15 <0.16}).
     */
    static boolean aboveRange(VersionScheme versions, String range, String version) {
        Matcher matcher = VERSION_NUMBER.matcher(range);
        boolean any = false;
        while (matcher.find()) {
            any = true;
            try {
                if (versions.compare(version, matcher.group()) <= 0) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return any;
    }

    private static void addTo(Map<String, Set<String>> map, String key, String value) {
        Set<String> set = map.get(key);
        if (set == null) {
            set = new LinkedHashSet<>();
            map.put(key, set);
        }
        set.add(value);
    }

    public static List<Unmet> findUnmet(Map<String, ModEntry> mods, Dist dist, Set<String> platformIds,
                                        VersionScheme versions) {
        List<Unmet> unmet = new ArrayList<>();
        for (ModEntry dependent : mods.values()) {
            for (Dependency rule : dependent.dependencies()) {
                if (!rule.side().appliesTo(dist) || platformIds.contains(rule.modId()) || !rule.enforcesVersion()
                        || rule.rangeSpec().isEmpty()) {
                    continue;
                }
                ModEntry dependency = mods.get(rule.modId());
                if (dependency == null || dependency == dependent) {
                    continue;
                }
                Boolean satisfied;
                try {
                    satisfied = versions.satisfies(rule.rangeSpec(), dependency.version());
                } catch (RuntimeException e) {
                    satisfied = null;
                }
                if (satisfied == null || satisfied) {
                    continue;
                }
                // An older loader than a mod needs is fixed by updating the loader, not by relaxing the rule
                if (dependency.isLoader() && !aboveRange(versions, rule.rangeSpec(), dependency.version())) {
                    continue;
                }
                unmet.add(new Unmet(dependent, dependency, rule));
            }
        }
        return unmet;
    }

    private static void logSummary(List<PairReport> reports, long millis) {
        for (PairReport r : reports) {
            String head = r.pairName() + " (wants " + r.requiredRange + ", found " + r.dependency.version() + ")";
            switch (r.decision) {
                case APPROVED:
                    LwLog.warn("Linkwright approved " + head + ": " + r.linksChecked + " links verified"
                            + (r.patches.isEmpty() ? "" : ", " + r.patches.size() + " change(s) adapted"));
                    break;
                case APPROVED_FORCED:
                    LwLog.warn("Linkwright force-approved " + head + " despite " + r.problems.size()
                            + " problem(s); see logs/linkwright-report.txt");
                    break;
                case REFUSED:
                    LwLog.warn("Linkwright refused " + head + ": " + r.problems.size() + " link problem(s)");
                    for (int i = 0; i < Math.min(3, r.problems.size()); i++) {
                        Problem p = r.problems.get(i);
                        LwLog.warn("  " + p);
                    }
                    break;
                case REPORT_ONLY:
                    LwLog.info("Linkwright checked " + head + ": "
                            + (r.clean() ? "links check out" : r.problems.size() + " problem(s)"));
                    break;
                case IGNORED:
                    LwLog.info("Linkwright skipped " + head + " (ignored in config)");
                    break;
                default:
                    break;
            }
        }
        LwLog.info("Linkwright finished in " + millis + " ms; details in logs/linkwright-report.txt");
    }
}
