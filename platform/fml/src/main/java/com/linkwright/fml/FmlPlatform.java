package com.linkwright.fml;

import com.linkwright.LoaderPlatform;

/** A Forge-family loader, which lets Linkwright relax rules only during a window of its startup. */
public interface FmlPlatform extends LoaderPlatform {
    /** Whether {@link #relax} would take effect if called now. */
    boolean readyToRelax();
}
