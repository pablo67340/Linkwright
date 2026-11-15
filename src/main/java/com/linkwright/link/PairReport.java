package com.linkwright.link;

import com.linkwright.scan.ModEntry;

import java.util.List;

/** What Linkwright found for one "mod A wants a different version of mod B" rule. */
public final class PairReport {
    public enum Decision {
        APPROVED("approved: every link checks out"),
        APPROVED_FORCED("approved because config/linkwright.toml forces it, despite the problems below"),
        REFUSED("refused: left NeoForge's dependency error in place"),
        REPORT_ONLY("not applied: Linkwright is in report mode"),
        IGNORED("skipped: listed under ignore in config/linkwright.toml");

        public final String label;

        Decision(String label) {
            this.label = label;
        }
    }

    public final ModEntry dependent;
    public final ModEntry dependency;
    public final String requiredRange;
    public final boolean alreadyOverridden;
    public final List<Problem> problems;
    public final List<Bridge> bridges;
    public final int linksChecked;
    public final int unverifiable;
    public Decision decision;

    public PairReport(ModEntry dependent, ModEntry dependency, String requiredRange, boolean alreadyOverridden,
                      List<Problem> problems, List<Bridge> bridges, int linksChecked, int unverifiable) {
        this.dependent = dependent;
        this.dependency = dependency;
        this.requiredRange = requiredRange;
        this.alreadyOverridden = alreadyOverridden;
        this.problems = problems;
        this.bridges = bridges;
        this.linksChecked = linksChecked;
        this.unverifiable = unverifiable;
    }

    public boolean clean() {
        return problems.isEmpty();
    }

    public String pairName() {
        return dependent.modId() + "->" + dependency.modId();
    }
}
