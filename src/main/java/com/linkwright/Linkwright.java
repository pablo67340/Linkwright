package com.linkwright;

import com.linkwright.link.Patch;
import com.linkwright.link.ClassIndex;
import com.linkwright.link.Hierarchy;
import com.linkwright.link.LinkChecker;
import com.linkwright.link.PairReport;
import com.linkwright.scan.Dependency;
import com.linkwright.scan.Dist;
import com.linkwright.scan.ModEntry;
import com.linkwright.scan.ModJar;
import com.linkwright.scan.ModScanner;
import com.linkwright.scan.PlatformJars;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds every mod-to-mod version rule the installed mods break, verifies each one at the bytecode
 * level, and decides which to approve and which call sites to bridge.
 */
public final class Linkwright {
    public static final String VERSION = Linkwright.class.getPackage().getImplementationVersion() == null
            ? "dev" : Linkwright.class.getPackage().getImplementationVersion();

    /** Rules about the game and loader themselves are never Linkwright's to relax. */
    private static final Set<String> PLATFORM_IDS = Set.of("minecraft", "neoforge", "forge", "fml", "javafml", "java");

    public record Unmet(ModEntry dependent, ModEntry dependency, Dependency rule) {
    }

    /**
     * @param overrides dependency removals to add to FML's overrides, by dependent mod id
     * @param patches   call sites, field accesses and methods to adapt as classes load
     */
    public record Result(Map<String, List<String>> overrides, List<Patch> patches, List<PairReport> reports) {
        public static final Result EMPTY = new Result(Map.of(), List.of(), List.of());
    }

    private Linkwright() {
    }

    /** A result plus every rule that ends up relaxed, including ones fml.toml already relaxed. */
    record Analysis(Result result, Map<String, Set<String>> approved, int modCount) {
    }

    public static Result run(Path gameDir, Dist dist) throws Exception {
        long start = System.nanoTime();
        LinkwrightConfig config = LinkwrightConfig.load(gameDir.resolve("config").resolve("linkwright.toml"));
        if (config.mode() == LinkwrightConfig.Mode.OFF) {
            LwLog.info("Linkwright is off (config/linkwright.toml)");
            return Result.EMPTY;
        }
        Map<String, Set<String>> existing = FmlToml.readRemovals(gameDir.resolve("config").resolve("fml.toml"));
        Analysis analysis = analyze(gameDir.resolve("mods"), PlatformJars.fromLaunch(dist), existing, config, dist);
        ReportWriter.write(gameDir.resolve("logs").resolve("linkwright-report.txt"), config, dist,
                analysis.result().reports(), analysis.approved());
        if (analysis.result().reports().isEmpty()) {
            LwLog.info("Linkwright: all " + analysis.modCount() + " mods' dependency versions are satisfied");
        } else {
            logSummary(analysis.result().reports(), (System.nanoTime() - start) / 1_000_000);
        }
        return analysis.result();
    }

    static Analysis analyze(Path modsDir, List<Path> platformJars, Map<String, Set<String>> existing,
                            LinkwrightConfig config, Dist dist) throws Exception {
        List<ModJar> platform = platformJars.stream().map(ModJar::top).toList();
        try (ModScanner.ModSet mods = ModScanner.scan(modsDir)) {
            List<Unmet> unmet = findUnmet(mods.mods(), dist);
            if (unmet.isEmpty()) {
                return new Analysis(Result.EMPTY, Map.of(), mods.mods().size());
            }

            List<ModJar> indexed = new ArrayList<>(mods.jars());
            indexed.addAll(platform);
            Hierarchy hierarchy = new Hierarchy(new ClassIndex(indexed));
            LinkChecker checker = new LinkChecker(hierarchy);
            List<PairReport> reports = new ArrayList<>();
            Map<String, List<String>> overrides = new LinkedHashMap<>();
            Map<String, Set<String>> approvedAll = new LinkedHashMap<>();
            List<Patch> patches = new ArrayList<>();

            for (Unmet u : unmet) {
                String pair = (u.dependent().modId() + "->" + u.dependency().modId());
                boolean already = existing.getOrDefault(u.dependent().modId(), Set.of()).contains(u.dependency().modId());
                PairReport report = checker.check(u.dependent(), u.dependency(), u.rule().rangeSpec(), already);
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
                    approvedAll.computeIfAbsent(u.dependent().modId(), k -> new LinkedHashSet<>()).add(u.dependency().modId());
                    if (!already) {
                        overrides.computeIfAbsent(u.dependent().modId(), k -> new ArrayList<>()).add(u.dependency().modId());
                    }
                }
                // A rule the pack already overrides in fml.toml loads either way, so bridging whatever
                // can be bridged only removes crashes.
                if (config.bridgeCalls() && (approved || already)) {
                    patches.addAll(report.patches);
                }
            }

            return new Analysis(new Result(overrides, List.copyOf(patches), reports), approvedAll, mods.mods().size());
        } finally {
            platform.forEach(ModJar::close);
        }
    }

    public static List<Unmet> findUnmet(Map<String, ModEntry> mods, Dist dist) {
        List<Unmet> unmet = new ArrayList<>();
        for (ModEntry dependent : mods.values()) {
            for (Dependency rule : dependent.dependencies()) {
                if (!rule.side().appliesTo(dist) || PLATFORM_IDS.contains(rule.modId()) || rule.range() == null
                        || (rule.type() != Dependency.Type.REQUIRED && rule.type() != Dependency.Type.OPTIONAL)) {
                    continue;
                }
                ModEntry dependency = mods.get(rule.modId());
                if (dependency == null || dependency == dependent || rule.range().containsVersion(dependency.artifactVersion())) {
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
                case APPROVED -> LwLog.warn("Linkwright approved " + head + ": " + r.linksChecked + " links verified"
                        + (r.patches.isEmpty() ? "" : ", " + r.patches.size() + " change(s) adapted"));
                case APPROVED_FORCED -> LwLog.warn("Linkwright force-approved " + head + " despite "
                        + r.problems.size() + " problem(s); see logs/linkwright-report.txt");
                case REFUSED -> {
                    LwLog.warn("Linkwright refused " + head + ": " + r.problems.size() + " link problem(s)");
                    r.problems.stream().limit(3).forEach(p -> LwLog.warn("  " + p));
                }
                case REPORT_ONLY -> LwLog.info("Linkwright checked " + head + ": "
                        + (r.clean() ? "links check out" : r.problems.size() + " problem(s)"));
                case IGNORED -> LwLog.info("Linkwright skipped " + head + " (ignored in config)");
            }
        }
        LwLog.info("Linkwright finished in " + millis + " ms; details in logs/linkwright-report.txt");
    }
}
