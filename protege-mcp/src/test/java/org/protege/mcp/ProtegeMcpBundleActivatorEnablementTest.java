package org.protege.mcp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the opt-out semantics of
 * {@link ProtegeMcpBundleActivator#enabledByDefault(String, String)}.
 * The MCP server is enabled by default; an explicit falsy value on either the
 * system property
 * or the environment variable disables it.
 */
public class ProtegeMcpBundleActivatorEnablementTest {

    @Test
    public void defaultsToEnabledWhenNeitherFlagIsSet() {
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault(null, null));
    }

    @Test
    public void propertyExplicitTrueEnables() {
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault("true", null));
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault("yes", null));
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault("1", null));
    }

    @Test
    public void propertyExplicitFalseDisables() {
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault("false", null));
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault("FALSE", null));
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault("0", null));
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault("no", null));
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault("off", null));
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault("disabled", null));
    }

    @Test
    public void envFalsyDisablesWhenPropertyAbsent() {
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault(null, "false"));
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault(null, "0"));
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault(null, "Off"));
    }

    @Test
    public void envTruthyOrUnknownLeavesEnabled() {
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault(null, "true"));
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault(null, "1"));
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault(null, "anything-else"));
    }

    @Test
    public void propertyTakesPrecedenceOverEnv() {
        // Property explicitly true beats falsy env.
        assertTrue(ProtegeMcpBundleActivator.enabledByDefault("true", "false"));
        // Property explicitly false beats truthy env.
        assertFalse(ProtegeMcpBundleActivator.enabledByDefault("false", "true"));
    }

    @Test
    public void isFalsyHandlesWhitespaceAndCase() {
        assertTrue(ProtegeMcpBundleActivator.isFalsy("  FALSE  "));
        assertTrue(ProtegeMcpBundleActivator.isFalsy("No"));
        assertFalse(ProtegeMcpBundleActivator.isFalsy("true"));
        assertFalse(ProtegeMcpBundleActivator.isFalsy(""));
        assertFalse(ProtegeMcpBundleActivator.isFalsy(null));
    }
}
