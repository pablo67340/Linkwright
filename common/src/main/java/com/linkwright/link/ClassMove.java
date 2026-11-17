package com.linkwright.link;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Points every reference in one class of the dependent mod at the new name of dependency classes
 * that moved to another package. Applied before any other patch on the class, since those are
 * planned against the moved names.
 */
public final class ClassMove implements Patch {
    public final String targetClass;
    /** Old internal name to new internal name. */
    public final Map<String, String> moves;

    public ClassMove(String targetClass, Map<String, String> moves) {
        this.targetClass = targetClass;
        this.moves = Collections.unmodifiableMap(new TreeMap<>(moves));
    }

    @Override
    public String targetClass() {
        return targetClass;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder(Bridge.dotted(targetClass)).append(": ");
        boolean first = true;
        for (Map.Entry<String, String> move : moves.entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            first = false;
            sb.append(Bridge.dotted(move.getKey())).append(" is now ").append(Bridge.dotted(move.getValue()));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
