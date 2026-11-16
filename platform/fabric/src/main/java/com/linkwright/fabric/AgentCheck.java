package com.linkwright.fabric;

import com.linkwright.LwLog;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.nio.file.Paths;

/** The jar is also a Fabric mod, only so it can say so when the agent argument is missing. */
public final class AgentCheck implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (System.getProperty(LinkwrightAgent.ACTIVE) == null) {
            LwLog.warn("Linkwright is installed but not active. Add this JVM argument to the launch profile: "
                    + "-javaagent:mods/" + jarName());
        }
    }

    private static String jarName() {
        try {
            return Paths.get(AgentCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getFileName().toString();
        } catch (Exception e) {
            return "linkwright-fabric.jar";
        }
    }
}
