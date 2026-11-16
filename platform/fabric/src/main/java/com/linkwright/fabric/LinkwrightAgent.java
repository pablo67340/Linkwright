package com.linkwright.fabric;

import java.lang.instrument.Instrumentation;

/**
 * Fabric Loader checks every version rule before any mod's code runs, so on Fabric Linkwright
 * starts as a Java agent: {@code -javaagent:mods/linkwright-fabric-<version>.jar}.
 *
 * <p>The agent's class loader may lack ASM (Fabric's server launcher keeps its libraries in a
 * class loader of its own), so this side never touches ASM or the core. {@link AgentBridge}
 * starts the core next to Fabric Loader once the loader's classes appear.
 */
public final class LinkwrightAgent {
    /** Set once the agent runs, so the mod half can tell players when it was left out. */
    static final String ACTIVE = "linkwright.agent";

    private LinkwrightAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        if (System.getProperty(ACTIVE) != null) {
            return;
        }
        System.setProperty(ACTIVE, "true");
        instrumentation.addTransformer(new AgentBridge(
                LinkwrightAgent.class.getProtectionDomain().getCodeSource().getLocation()));
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        premain(args, instrumentation);
    }
}
