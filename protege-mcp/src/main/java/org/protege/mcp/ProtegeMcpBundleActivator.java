package org.protege.mcp;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtegeMcpBundleActivator implements BundleActivator {

    public static final String ENABLED_PROPERTY = "protege.mcp.enabled";
    public static final String ENABLED_ENVIRONMENT_VARIABLE = "PROTEGE_MCP_ENABLED";

    private static final Logger logger = LoggerFactory.getLogger(ProtegeMcpBundleActivator.class);

    private ProtegeMcpServerController controller;

    @Override
    public void start(BundleContext context) {
        if (!isEnabled()) {
            logger.info("Protege MCP server disabled. Set {}=true or {}=true to enable it.", ENABLED_PROPERTY,
                    ENABLED_ENVIRONMENT_VARIABLE);
            return;
        }
        controller = new ProtegeMcpServerController(new ProtegeMcpServer(new ProtegeMcpToolExecutor()));
        controller.start();
        logger.info("Protege MCP server started on stdio");
    }

    @Override
    public void stop(BundleContext context) {
        if (controller != null) {
            controller.stop();
            controller = null;
        }
    }

    private boolean isEnabled() {
        if (Boolean.getBoolean(ENABLED_PROPERTY)) {
            return true;
        }
        String envValue = System.getenv(ENABLED_ENVIRONMENT_VARIABLE);
        return envValue != null && Boolean.parseBoolean(envValue);
    }
}
