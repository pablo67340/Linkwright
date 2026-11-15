package com.linkwright.scan;

/**
 * One dependency rule a mod declares, already translated into Linkwright's terms by the
 * {@link ModReader} for its loader.
 */
public final class Dependency {
    public enum Type {
        /** Must be present in range, or the loader stops. */
        REQUIRED,
        /** May be absent, but must be in range when present. */
        OPTIONAL,
        INCOMPATIBLE,
        DISCOURAGED,
        /** Only a warning when unmet; nothing for Linkwright to do. */
        ADVISORY
    }

    public enum Side {
        BOTH, CLIENT, SERVER;

        public boolean appliesTo(Dist dist) {
            return this == BOTH || (this == CLIENT) == (dist == Dist.CLIENT);
        }
    }

    private final String modId;
    private final String rangeSpec;
    private final Type type;
    private final Side side;

    /**
     * @param rangeSpec the range as written, in the loader's own syntax; blank means any version
     */
    public Dependency(String modId, String rangeSpec, Type type, Side side) {
        this.modId = modId;
        this.rangeSpec = rangeSpec == null ? "" : rangeSpec.trim();
        this.type = type;
        this.side = side;
    }

    public String modId() {
        return modId;
    }

    public String rangeSpec() {
        return rangeSpec;
    }

    public Type type() {
        return type;
    }

    public Side side() {
        return side;
    }

    /** Whether the loader refuses to start when this rule is unmet by an installed mod. */
    public boolean enforcesVersion() {
        return type == Type.REQUIRED || type == Type.OPTIONAL;
    }

    @Override
    public String toString() {
        return modId + " " + rangeSpec + " (" + type.name().toLowerCase(java.util.Locale.ROOT) + ")";
    }
}
