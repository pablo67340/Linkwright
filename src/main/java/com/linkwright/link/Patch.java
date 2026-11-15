package com.linkwright.link;

/** A change Linkwright makes to one class of the dependent mod as it loads. */
public sealed interface Patch permits Bridge, FieldBridge, ImplBridge {
    String targetClass();

    String describe();

    static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
