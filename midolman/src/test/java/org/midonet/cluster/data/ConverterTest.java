/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
