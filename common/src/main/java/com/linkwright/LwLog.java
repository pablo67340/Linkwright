package com.linkwright;

/**
 * Logs through log4j, which every Minecraft version since 1.7 ships. A Java agent can run before
 * log4j is on the class path, so this falls back to standard output unless the platform supplies
 * its own logger.
 */
public final class LwLog {
    private static volatile Sink sink = createSink();

    private LwLog() {
    }

    public static void info(String message) {
        sink.log(0, message, null);
    }

    public static void warn(String message) {
        sink.log(1, message, null);
    }

    public static void error(String message, Throwable t) {
        sink.log(2, message, t);
    }

    public static void debug(String message) {
        sink.log(-1, message, null);
    }

    public static void use(Sink replacement) {
        sink = replacement;
    }

    public interface Sink {
        /** @param level -1 debug, 0 info, 1 warn, 2 error */
        void log(int level, String message, Throwable t);
    }

    private static Sink createSink() {
        try {
            final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger("Linkwright");
            return new Sink() {
                @Override
                public void log(int level, String message, Throwable t) {
                    switch (level) {
                        case -1:
                            logger.debug(message);
                            break;
                        case 0:
                            logger.info(message);
                            break;
                        case 1:
                            logger.warn(message);
                            break;
                        default:
                            logger.error(message, t);
                            break;
                    }
                }
            };
        } catch (Throwable missing) {
            return new Sink() {
                @Override
                public void log(int level, String message, Throwable t) {
                    if (level < 0) {
                        return;
                    }
                    System.out.println("[Linkwright] " + message);
                    if (t != null) {
                        t.printStackTrace(System.out);
                    }
                }
            };
        }
    }
}
