package com.linkwright.oldml;

import net.minecraftforge.fml.common.Mod;

/**
 * Forge 1.14 to 1.16 require a {@code @Mod} class for every mod in a mods.toml, and Linkwright's
 * jar is loaded as a mod there (see {@link LinkwrightLocator}). Everything happens before the game
 * starts, so there is nothing to do here.
 */
@Mod("linkwright")
public final class LinkwrightMod {
}
