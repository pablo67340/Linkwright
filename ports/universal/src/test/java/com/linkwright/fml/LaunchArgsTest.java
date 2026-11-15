package com.linkwright.fml;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchArgsTest {
    @Test
    void unwrapsCodeSourceLocations() throws Exception {
        String jar = Paths.get("libs", "cpw", "mods", "modlauncher", "10.0.9", "modlauncher-10.0.9.jar")
                .toAbsolutePath().toUri().toString();
        assertEquals(Paths.get(java.net.URI.create(jar)), LaunchArgs.jarFile(jar));
        assertEquals(Paths.get(java.net.URI.create(jar)), LaunchArgs.jarFile("jar:" + jar + "!/"));
        String union = "union:" + jar.substring("file:".length()) + "%2365!/";
        assertEquals(Paths.get(java.net.URI.create(jar)), LaunchArgs.jarFile(union));
    }
}
