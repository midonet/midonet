/**
 * Unit tests for Converter.
 */
package org.midonet.cluster.data;

import static org.junit.Assert.*;

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
		bridge.setInboundFilter(null);
		bridge.setOutboundFilter(null);
		bridge.setTunnelKey(1);
		bridge.setProperty(Bridge.Property.tenant_id, "tenant_1");
		bridge.addTag("foo");
		BridgeConfig bridgeConfig = Converter.toBridgeConfig(bridge);

		assertEquals("bridge_1", bridgeConfig.name);
		assertNull(bridgeConfig.inboundFilter);
		assertNull(bridgeConfig.outboundFilter);
		assertEquals(1, bridgeConfig.tunnelKey);
		assertEquals(1, bridgeConfig.properties.size());
		assertEquals("tenant_1",
				     bridgeConfig.properties.get("tenant_id"));
		assertEquals(1, bridgeConfig.tagSize());
		assertTrue(bridgeConfig.containsTag("foo"));
	}
	
	@Test
	public void testFromBridgeConfig() {
		BridgeConfig bridgeConfig = new BridgeConfig();
		bridgeConfig.name = "bridge_1";
		bridgeConfig.inboundFilter = null;
		bridgeConfig.outboundFilter = null;
		bridgeConfig.tunnelKey = 1;
		bridgeConfig.properties.put("tenant_id", "tenant_1");
		bridgeConfig.addTag("foo");
		Bridge bridge = Converter.fromBridgeConfig(bridgeConfig);
		
		assertEquals("bridge_1", bridge.getName());
		assertNull(bridge.getInboundFilter());
		assertNull(bridge.getOutboundFilter());
		assertEquals(1, bridge.getTunnelKey());
		assertEquals("tenant_1", bridge.getProperty(Bridge.Property.tenant_id));
		assertEquals(1, bridge.tagSize());
		assertTrue(bridge.containsTag("foo"));
	}
}
