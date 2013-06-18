/**
 * Unit tests for Converter.
 */
package org.midonet.cluster.data;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;

/**
 * @author tomohiko
 *
 */
public class ConverterTest {
    @Test
    public void testToBridgeConfig() {
        Bridge bridge = new Bridge();
        bridge.setName("bridge_1");
        bridge.setInboundFilter(UUID.randomUUID());
        bridge.setOutboundFilter(UUID.randomUUID());
        bridge.setTunnelKey(1);
        bridge.setProperty(Bridge.Property.tenant_id, "tenant_1");
        BridgeConfig bridgeConfig = Converter.toBridgeConfig(bridge);

        assertEquals("bridge_1", bridgeConfig.name);
        assertEquals(bridge.getInboundFilter(), bridgeConfig.inboundFilter);
        assertEquals(bridge.getOutboundFilter(), bridgeConfig.outboundFilter);
        assertEquals(1, bridgeConfig.tunnelKey);
        assertEquals(1, bridgeConfig.properties.size());
        assertEquals("tenant_1",
                     bridgeConfig.properties.get("tenant_id"));
    }

    @Test
    public void testFromBridgeConfig() {
        BridgeConfig bridgeConfig = new BridgeConfig();
        bridgeConfig.name = "bridge_1";
        bridgeConfig.inboundFilter = UUID.randomUUID();
        bridgeConfig.outboundFilter = UUID.randomUUID();
        bridgeConfig.tunnelKey = 1;
        bridgeConfig.properties.put("tenant_id", "tenant_1");
        Bridge bridge = Converter.fromBridgeConfig(bridgeConfig);

        assertEquals("bridge_1", bridge.getName());
        assertEquals(bridgeConfig.inboundFilter, bridge.getInboundFilter());
        assertEquals(bridgeConfig.outboundFilter, bridge.getOutboundFilter());
        assertEquals(1, bridge.getTunnelKey());
        assertEquals("tenant_1", bridge.getProperty(Bridge.Property.tenant_id));
    }
}
