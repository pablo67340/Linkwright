package com.linkwright.scan;

public enum Dist {
    CLIENT, SERVER;

    public static Dist fromLaunchTarget(String launchTarget) {
        return launchTarget != null && launchTarget.toLowerCase(java.util.Locale.ROOT).contains("server") ? SERVER : CLIENT;
    }
}
