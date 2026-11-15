package com.linkwright.link;

import java.util.List;

/**
 * A rewrite of every call to {@code owner.name oldDesc} inside one method, so it calls the
 * signature the dependency has now.
 *
 * @param strategy where the extra arguments go: after the existing ones, or swapped in front of
 *                 the last one
 * @param popSize  stack slots to discard afterwards when the new method returns a value and the
 *                 old one was {@code void}
 */
public record Bridge(String callerClass, String callerName, String callerDesc, int opcode, String owner,
                     String name, String oldDesc, String newDesc, boolean itf, Strategy strategy,
                     List<ValueSource> sources, int popSize) {

    public enum Strategy {
        APPEND, SWAP_BEFORE_LAST
    }

    /**
     * Where an added argument comes from: a parameter (or {@code this}) of the calling method,
     * optionally followed by one no-argument getter on it.
     */
    public record ValueSource(int slot, String slotType, String getterOwner, String getterName,
                              String getterDesc, boolean getterItf, String description) {
        public boolean hasGetter() {
            return getterName != null;
        }
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(callerClass.replace('/', '.')).append('.').append(callerName).append(": ")
                .append(owner.replace('/', '.')).append('.').append(name).append(oldDesc)
                .append(" -> ").append(newDesc);
        if (!sources.isEmpty()) {
            sb.append(" with ");
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(sources.get(i).description());
            }
        }
        return sb.toString();
    }
}
