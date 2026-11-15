package com.linkwright.link;

/** One way the dependent mod's code no longer lines up with the dependency it is running against. */
public final class Problem {
    public enum Kind {
        MISSING_CLASS("missing class"),
        MISSING_METHOD("missing method"),
        MISSING_FIELD("missing field"),
        MIXIN_TARGET("mixin target class missing"),
        MIXIN_MEMBER("mixin target member missing"),
        UNIMPLEMENTED("abstract method not implemented");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public final Kind kind;
    public final String subject;
    public final String firstSeenIn;
    public final String detail;
    private int occurrences = 1;

    public Problem(Kind kind, String subject, String firstSeenIn, String detail) {
        this.kind = kind;
        this.subject = subject;
        this.firstSeenIn = firstSeenIn;
        this.detail = detail;
    }

    String key() {
        return kind + "|" + subject;
    }

    void seenAgain() {
        occurrences++;
    }

    public int occurrences() {
        return occurrences;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(kind.label).append(": ").append(subject)
                .append(" (used in ").append(firstSeenIn);
        if (occurrences > 1) {
            sb.append(" and ").append(occurrences - 1).append(" more place").append(occurrences > 2 ? "s" : "");
        }
        sb.append(')');
        if (detail != null && !detail.isEmpty()) {
            sb.append(" - ").append(detail);
        }
        return sb.toString();
    }
}
