package com.linkwright.fabric;

import com.linkwright.LwLog;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Fabric Loader's own logger, which reaches the game log even before log4j is visible to the
 * loader's class loader.
 */
final class FabricLog implements LwLog.Sink {
    private static final LogCategory CATEGORY = category();

    private static LogCategory category() {
        try {
            return LogCategory.createCustom("Linkwright");
        } catch (NoSuchMethodError beforeLoader0_14_x) {
            return LogCategory.GENERAL;
        }
    }

    @Override
    public void log(int level, String message, Throwable t) {
        switch (level) {
            case -1:
                Log.debug(CATEGORY, message);
                break;
            case 0:
                Log.info(CATEGORY, message);
                break;
            case 1:
                Log.warn(CATEGORY, message);
                break;
            default:
                Log.error(CATEGORY, message, t);
                break;
        }
    }
}
