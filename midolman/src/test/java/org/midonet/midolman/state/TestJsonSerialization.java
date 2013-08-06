/*
 * Copyright 2013 Midokura Europe SARL
 */

package org.midonet.midolman.state;

import java.io.IOException;
import java.util.UUID;

import org.junit.Test;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager.Host;
import org.midonet.midolman.state.PortDirectory.*;
import org.midonet.midolman.version.serialization.JsonVersionZkSerializer;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;


import static junit.framework.Assert.assertEquals;

public class TestJsonSerialization {

    @Test
    public void testIPv4Addr() throws IOException {
        IPv4Addr ip = IPv4Addr.fromString("10.1.2.3");
        String json = JsonVersionZkSerializer.objToJsonString(ip);
        assertEquals("\"10.1.2.3\"", json);
        IPv4Addr ip2 =
                JsonVersionZkSerializer.jsonStringToObj(json, IPv4Addr.class);
        assertEquals(ip, ip2);
    }

    @Test
    public void testMac() throws IOException {
        MAC mac = MAC.fromString("aa:bb:cc:dd:ee:11");
        String json = JsonVersionZkSerializer.objToJsonString(mac);
        assertEquals("\"aa:bb:cc:dd:ee:11\"", json);
        MAC mac2 = JsonVersionZkSerializer.jsonStringToObj(json, MAC.class);
        assertEquals(mac, mac2);
    }

    @Test
    public void testHost() throws IOException {
        Host host = new Host(MAC.fromString("aa:bb:cc:dd:ee:11"),
                             IPv4Addr.fromString("10.1.2.3").toIntIPv4(),
                             "mars");
        String json = JsonVersionZkSerializer.objToJsonString(host);
        assertEquals(
            "{\"mac\":\"aa:bb:cc:dd:ee:11\",\"ip\":\"10.1.2.3\",\"name\":\"mars\"}",
            json);
        Host host2 = JsonVersionZkSerializer.jsonStringToObj(json, Host.class);
        assertEquals(host, host2);
    }

    @Test
    public void testMaterializedRouterPort() throws IOException {
        RouterPortConfig port = new RouterPortConfig(
            UUID.randomUUID(), 0x0a000000, 24,0x0a00000a,
            MAC.fromString("aa:bb:cc:dd:ee:00"), null, null);
        String json = JsonVersionZkSerializer.objToJsonString(port);
        RouterPortConfig port2 =
                JsonVersionZkSerializer.jsonStringToObj(json,
                RouterPortConfig.class);
        assertEquals(port, port2);
        // Now deserialize to the superclass.
        PortConfig port3 = JsonVersionZkSerializer.jsonStringToObj(json,
            PortConfig.class);
        assertEquals(port, port3);
    }

    @Test
    public void testMaterializedBridgePort() throws IOException {
        BridgePortConfig port = new BridgePortConfig(
            UUID.randomUUID());
        String json = JsonVersionZkSerializer.objToJsonString(port);
        BridgePortConfig port2 =
                JsonVersionZkSerializer.jsonStringToObj(json,
                BridgePortConfig.class);
        assertEquals(port, port2);
        // Now deserialize to the superclass.
        PortConfig port3 = JsonVersionZkSerializer.jsonStringToObj(json,
            PortConfig.class);
        assertEquals(port, port3);
    }

    @Test
    public void testDeserializationWithMissingJsonField() throws IOException {
        RouterPortConfig port = new RouterPortConfig(
            UUID.randomUUID(), 0x0a000000, 24,0x0a00000a,
            MAC.fromString("aa:bb:cc:dd:ee:00"), null, null);
        String json = JsonVersionZkSerializer.objToJsonString(port);
        // Test backwards compatibility by removing 'v1ApiType' from
        // the json string.
        json = json.replaceAll("\"v1ApiType\":null,", "");
        RouterPortConfig port2 =
            JsonVersionZkSerializer.jsonStringToObj(json,
                RouterPortConfig.class);
        assertEquals(port, port2);
        // Now deserialize to the superclass.
        PortConfig port3 = JsonVersionZkSerializer.jsonStringToObj(json,
            PortConfig.class);
        assertEquals(port, port3);
    }
}
