/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.models;

import java.io.IOException;
import java.io.StringReader;

import org.junit.Test;

import org.midonet.cluster.models.Devices.Bridge;

import com.google.protobuf.TextFormat;

import static org.junit.Assert.*;

/**
 * Tests MidoNet model classes generated from Protocol Buffer definitions.
 */
public class ClusterModelsTest {

    public final String bridgeProto =
            "id {" +
            "  msb: -6127972624955391458" +
            "  lsb: -5217055138508627827" +
            "} " +
            "name: 'mido-bridge' " +
            "admin_state_up: true " +
            "tunnel_key: -1 " +
            "inbound_filter_id {" +
            "  msb: 96171025614589570" +
            "  lsb: -5669586258044381317" +
            "}";

    @Test
    public void testBridgeProto() throws IOException {
        Bridge.Builder bridgeBuilder = Bridge.newBuilder();
        StringReader bridgeReader = new StringReader(this.bridgeProto);
        TextFormat.merge(bridgeReader, bridgeBuilder);
        Bridge bridge = bridgeBuilder.build();

        assertEquals(-6127972624955391458L,
                     bridge.getId().getMsb());
        assertEquals(-5217055138508627827L,
                     bridge.getId().getLsb());
        assertEquals("mido-bridge", bridge.getName());
        assertTrue(bridge.getAdminStateUp());
        assertEquals(96171025614589570L,
                     bridge.getInboundFilterId().getMsb());
        assertEquals(-5669586258044381317L,
                     bridge.getInboundFilterId().getLsb());
    }
}
