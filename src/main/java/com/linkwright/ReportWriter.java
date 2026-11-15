package com.linkwright;

import com.linkwright.link.Patch;
import com.linkwright.link.PairReport;
import com.linkwright.link.Problem;
import com.linkwright.scan.Dist;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes {@code logs/linkwright-report.txt}. */
final class ReportWriter {
    private static final int MAX_PROBLEMS_PER_PAIR = 40;

    private ReportWriter() {
    }

    static void write(Path file, LinkwrightConfig config, Dist dist, List<PairReport> reports,
                      Map<String, Set<String>> approved) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, render(config, dist, reports, approved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LwLog.warn("Could not write " + file + ": " + e);
        }
    }

    static String render(LinkwrightConfig config, Dist dist, List<PairReport> reports, Map<String, Set<String>> approved) {
        StringBuilder out = new StringBuilder();
        out.append("Linkwright ").append(Linkwright.VERSION).append(" report, ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(", ").append(dist.name().toLowerCase()).append('\n');
        out.append("Mode: ").append(config.mode().name().toLowerCase())
                .append(config.bridgeCalls() ? "" : " (call bridging disabled)").append("\n\n");

        if (reports.isEmpty()) {
            out.append("Every mod's dependency versions are satisfied. Nothing to do.\n");
            return out.toString();
        }

        for (PairReport r : reports) {
            out.append(r.dependent).append("  ->  ").append(r.dependency).append('\n');
            out.append("  Rule:     ").append(r.dependent.modId()).append(" asks for ").append(r.dependency.modId())
                    .append(' ').append(r.requiredRange).append(", installed is ").append(r.dependency.version()).append('\n');
            if (r.alreadyOverridden) {
                out.append("  Note:     config/fml.toml already overrides this rule\n");
            }
            out.append("  Decision: ").append(r.decision.label).append('\n');
            out.append("  Checked:  ").append(r.linksChecked).append(" links");
            if (r.unverifiable > 0) {
                out.append(" (").append(r.unverifiable)
                        .append(" resolve into Minecraft, NeoForge or a library and are assumed present)");
            }
            out.append('\n');
            if (!r.patches.isEmpty()) {
                out.append("  Adapted:  ").append(r.patches.size()).append(" change(s)\n");
                for (Patch b : r.patches) {
                    out.append("    - ").append(b.describe()).append('\n');
                }
            }
            if (r.problems.isEmpty()) {
                out.append("  Problems: none\n");
            } else {
                out.append("  Problems: ").append(r.problems.size()).append('\n');
                r.problems.stream().limit(MAX_PROBLEMS_PER_PAIR).forEach(p -> out.append("    - ").append(p).append('\n'));
                if (r.problems.size() > MAX_PROBLEMS_PER_PAIR) {
                    out.append("    ... and ").append(r.problems.size() - MAX_PROBLEMS_PER_PAIR).append(" more\n");
                }
                long bridgeable = r.problems.stream().filter(p -> p.kind == Problem.Kind.MISSING_METHOD).count();
                if (bridgeable > 0 && r.decision == PairReport.Decision.REFUSED) {
                    out.append("    Missing methods with a reason listed could not be adapted safely.\n");
                }
            }
            out.append('\n');
        }

        if (!approved.isEmpty()) {
            out.append("To ship these approvals without Linkwright, put this line in config/fml.toml\n")
                    .append("(merge it with any dependencyOverrides line already there). This only relaxes the\n")
                    .append("version check; the adapted changes above still need Linkwright or a hand-written patch.\n  ")
                    .append(FmlToml.overridesLine(approved)).append('\n');
        }
        return out.toString();
    }
}
