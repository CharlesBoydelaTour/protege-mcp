package org.protege.mcp;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Iterator;

public class ProtegeMcpBundleActivator implements BundleActivator {

    public static final String ENABLED_PROPERTY = "protege.mcp.enabled";
    public static final String ENABLED_ENVIRONMENT_VARIABLE = "PROTEGE_MCP_ENABLED";

    private static final Logger logger = LoggerFactory.getLogger(ProtegeMcpBundleActivator.class);

    private ProtegeMcpServerController controller;
    private PrintStream originalOut;

    @Override
    public void start(BundleContext context) {
        if (!isEnabled()) {
            logger.info("Protege MCP server disabled. Set {}=true or {}=true to enable it.", ENABLED_PROPERTY,
                    ENABLED_ENVIRONMENT_VARIABLE);
            return;
        }
        // Capture the original stdout BEFORE redirecting it so JSON-RPC frames
        // continue to reach fd 1, and reroute every other stdout writer to stderr
        // to keep the MCP stdio channel free of pollution.
        originalOut = System.out;
        InputStream originalIn = System.in;
        System.setOut(System.err);
        rerouteLogbackConsoleAppendersToStderr();

        controller = new ProtegeMcpServerController(
                new ProtegeMcpServer(new ProtegeMcpToolExecutor()),
                originalIn,
                originalOut);
        controller.start();
        logger.info("Protege MCP server started on stdio");
    }

    @Override
    public void stop(BundleContext context) {
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        if (originalOut != null) {
            System.setOut(originalOut);
            originalOut = null;
        }
    }

    private boolean isEnabled() {
        if (Boolean.getBoolean(ENABLED_PROPERTY)) {
            return true;
        }
        String envValue = System.getenv(ENABLED_ENVIRONMENT_VARIABLE);
        return envValue != null && Boolean.parseBoolean(envValue);
    }

    /**
     * Reconfigure logback's root logger console appenders to write to System.err
     * instead of System.out so log lines do not corrupt the MCP stdio frame
     * stream. Uses reflection so the bundle does not require a hard logback
     * dependency at runtime.
     */
    private void rerouteLogbackConsoleAppendersToStderr() {
        try {
            Class<?> loggerFactoryCls = Class.forName("org.slf4j.LoggerFactory");
            Object lc = loggerFactoryCls.getMethod("getILoggerFactory").invoke(null);
            if (!"ch.qos.logback.classic.LoggerContext".equals(lc.getClass().getName())) {
                return;
            }
            Class<?> rootLoggerCls = Class.forName("ch.qos.logback.classic.Logger");
            Method getLogger = lc.getClass().getMethod("getLogger", String.class);
            Object root = getLogger.invoke(lc, "ROOT");
            Method iter = rootLoggerCls.getMethod("iteratorForAppenders");
            Iterator<?> it = (Iterator<?>) iter.invoke(root);
            Class<?> consoleAppenderCls = Class.forName("ch.qos.logback.core.ConsoleAppender");
            while (it.hasNext()) {
                Object appender = it.next();
                if (consoleAppenderCls.isInstance(appender)) {
                    Method stop = appender.getClass().getMethod("stop");
                    Method setTarget = appender.getClass().getMethod("setTarget", String.class);
                    Method start = appender.getClass().getMethod("start");
                    stop.invoke(appender);
                    setTarget.invoke(appender, "System.err");
                    start.invoke(appender);
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Logback not present – nothing to reconfigure.
        } catch (Throwable t) {
            logger.debug("Failed to reroute logback ConsoleAppenders to stderr: {}", t.toString());
        }
    }
}
