package com.linkwright;

import com.linkwright.link.PairReport;
import com.linkwright.link.Patch;
import com.linkwright.link.Problem;
import com.linkwright.util.Io;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Writes {@code logs/linkwright-report.txt}. */
final class ReportWriter {
    private static final int MAX_PROBLEMS_PER_PAIR = 40;

    private ReportWriter() {
    }

    static void write(Path file, LinkwrightConfig config, LoaderPlatform platform, List<PairReport> reports,
                      Map<String, Set<String>> approved, List<String> warnings) {
        try {
            String hint = approved.isEmpty() ? null : platform.overridesHint(approved);
            Io.writeString(file, render(config, platform.loaderName(), platform.dist().name(), reports, hint, warnings));
        } catch (Exception e) {
            LwLog.warn("Could not write " + file + ": " + e);
        }
    }

    /** @param overridesHint how to relax the approved rules without Linkwright, or {@code null} */
    static String render(LinkwrightConfig config, String loaderName, String dist, List<PairReport> reports,
                         String overridesHint, List<String> warnings) {
        StringBuilder out = new StringBuilder();
        out.append("Linkwright ").append(Linkwright.VERSION).append(" report, ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()))
                .append(", ").append(loaderName).append(' ').append(dist.toLowerCase(Locale.ROOT)).append('\n');
        out.append("Mode: ").append(config.mode().name().toLowerCase(Locale.ROOT))
                .append(config.bridgeCalls() ? "" : " (call bridging disabled)").append("\n\n");

        if (!warnings.isEmpty()) {
            out.append("Warnings (Linkwright cannot fix these, but the game will likely fail because of them):\n");
            for (String warning : warnings) {
                out.append("  - ").append(warning).append('\n');
            }
            out.append('\n');
        }

        if (reports.isEmpty()) {
            out.append("Every mod's dependency versions are satisfied. Nothing to do.\n");
            return out.toString();
        }

        for (PairReport r : reports) {
            out.append(r.dependent).append("  ->  ").append(r.dependency).append('\n');
            out.append("  Rule:     ").append(r.dependent.modId()).append(" asks for ").append(r.dependency.modId())
                    .append(' ').append(r.requiredRange).append(", installed is ").append(r.dependency.version()).append('\n');
            if (r.olderLoader) {
                out.append("  Note:     this mod asks for a later ").append(loaderName).append(" build of the same release")
                        .append(" line; every class, method, field and mixin target it uses in ").append(loaderName)
                        .append(" was checked\n");
            } else if (r.dependency.isLoader()) {
                out.append("  Note:     ").append(loaderName).append(" is newer than this mod allows; every class, method,")
                        .append(" field and mixin target it uses in ").append(loaderName).append(" was checked\n");
            }
            if (r.alreadyOverridden) {
                out.append("  Note:     ").append(loaderName).append("'s own config already relaxes this rule\n");
            }
            out.append("  Decision: ").append(r.decision.label).append('\n');
            out.append("  Checked:  ").append(r.linksChecked).append(" links");
            if (r.unverifiable > 0) {
                out.append(" (").append(r.unverifiable).append(" resolve into Minecraft, ").append(loaderName)
                        .append(" or a library and are assumed present)");
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
                boolean bridgeable = false;
                for (int i = 0; i < r.problems.size(); i++) {
                    Problem p = r.problems.get(i);
                    if (i < MAX_PROBLEMS_PER_PAIR) {
                        out.append("    - ").append(p).append('\n');
                    }
                    bridgeable |= p.kind == Problem.Kind.MISSING_METHOD;
                }
                if (r.problems.size() > MAX_PROBLEMS_PER_PAIR) {
                    out.append("    ... and ").append(r.problems.size() - MAX_PROBLEMS_PER_PAIR).append(" more\n");
                }
                if (bridgeable && r.decision == PairReport.Decision.REFUSED) {
                    out.append("    Missing methods with a reason listed could not be adapted safely.\n");
                }
            }
            out.append('\n');
        }

        if (overridesHint != null) {
            out.append(overridesHint);
            if (!overridesHint.endsWith("\n")) {
                out.append('\n');
            }
        }
        return out.toString();
    }
}
