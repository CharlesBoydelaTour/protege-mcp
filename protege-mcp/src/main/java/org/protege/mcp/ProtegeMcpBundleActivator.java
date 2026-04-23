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
    private ProtegeMcpTcpTransport tcpTransport;
    private PrintStream originalOut;

    @Override
    public void start(BundleContext context) {
        if (!enabledByDefault()) {
            logger.info("Protege MCP server disabled. Unset {} (or set to true) and unset {} to enable it.",
                    ENABLED_PROPERTY, ENABLED_ENVIRONMENT_VARIABLE);
            return;
        }
        if (!hasExplicitEnableFlag()) {
            logger.info("Protege MCP server enabled by default; set -D{}=false or {}=false to disable.",
                    ENABLED_PROPERTY, ENABLED_ENVIRONMENT_VARIABLE);
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

        if (ProtegeMcpTcpTransport.tcpEnabled()) {
            int port = ProtegeMcpTcpTransport.resolvePort();
            ProtegeMcpTcpTransport transport = new ProtegeMcpTcpTransport(port);
            try {
                transport.start();
                tcpTransport = transport;
            } catch (java.io.IOException e) {
                logger.warn("Protege MCP TCP transport failed to bind 127.0.0.1:{}: {}. Stdio remains available.",
                        port, e.getMessage());
            }
        } else {
            logger.info("Protege MCP TCP transport disabled via -D{}=false or {}=false.",
                    ProtegeMcpTcpTransport.ENABLED_PROPERTY,
                    ProtegeMcpTcpTransport.ENABLED_ENVIRONMENT_VARIABLE);
        }
    }

    @Override
    public void stop(BundleContext context) {
        if (tcpTransport != null) {
            tcpTransport.close();
            tcpTransport = null;
        }
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        if (originalOut != null) {
            System.setOut(originalOut);
            originalOut = null;
        }
    }

    /**
     * Compute whether the MCP server should start. The bundle is enabled by
     * default; either the {@value #ENABLED_PROPERTY} system property or the
     * {@value #ENABLED_ENVIRONMENT_VARIABLE} environment variable can be set
     * to a falsy value ({@code false|0|no|off|disabled}, case-insensitive) to
     * opt out. Any non-falsy explicit value (or absence of both) leaves the
     * server enabled.
     *
     * <p>
     * Package-private so {@code ProtegeMcpBundleActivatorEnablementTest}
     * can exercise the parsing rules without spinning up an OSGi container.
     */
    static boolean enabledByDefault() {
        return enabledByDefault(System.getProperty(ENABLED_PROPERTY),
                System.getenv(ENABLED_ENVIRONMENT_VARIABLE));
    }

    static boolean enabledByDefault(String propertyValue, String envValue) {
        if (propertyValue != null) {
            return !isFalsy(propertyValue);
        }
        if (envValue != null) {
            return !isFalsy(envValue);
        }
        return true;
    }

    static boolean isFalsy(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off") || v.equals("disabled");
    }

    private static boolean hasExplicitEnableFlag() {
        return System.getProperty(ENABLED_PROPERTY) != null
                || System.getenv(ENABLED_ENVIRONMENT_VARIABLE) != null;
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
