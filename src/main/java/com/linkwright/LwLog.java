package com.linkwright;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class LwLog {
    private static final Logger LOGGER = LogManager.getLogger("Linkwright");

    private LwLog() {
    }

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void warn(String message) {
        LOGGER.warn(message);
    }

    public static void error(String message, Throwable t) {
        LOGGER.error(message, t);
    }

    public static void debug(String message) {
        LOGGER.debug(message);
    }
}
