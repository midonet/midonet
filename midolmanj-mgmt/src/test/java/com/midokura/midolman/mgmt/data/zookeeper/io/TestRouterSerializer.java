/*
 * @(#)TestRouterSerializer        1.6 11/12/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

public class TestRouterSerializer {

    Serializer<RouterMgmtConfig> serializerMock = null;
    Serializer<RouterNameMgmtConfig> nameSerializerMock = null;
    Serializer<PeerRouterConfig> peerSerializerMock = null;
    RouterSerializer serializer = null;
    byte[] dummyBytes = { 1, 2, 3 };

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        serializerMock = Mockito.mock(Serializer.class);
        nameSerializerMock = Mockito.mock(Serializer.class);
        peerSerializerMock = Mockito.mock(Serializer.class);
        serializer = new RouterSerializer(serializerMock, nameSerializerMock,
                peerSerializerMock);
    }

    @Test
    public void testDeserializeGoodInput() throws Exception {
        serializer.deserialize(dummyBytes);
        Mockito.verify(serializerMock, Mockito.times(1)).bytesToObj(dummyBytes,
                RouterMgmtConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializeBadInput() throws Exception {
        Mockito.when(
                serializerMock.bytesToObj(dummyBytes, RouterMgmtConfig.class))
                .thenThrow(new IOException());
        serializer.deserialize(dummyBytes);
    }

    @Test
    public void testDeserializeNameGoodInput() throws Exception {
        serializer.deserializeName(dummyBytes);
        Mockito.verify(nameSerializerMock, Mockito.times(1)).bytesToObj(
                dummyBytes, RouterNameMgmtConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializeNameBadInput() throws Exception {
        Mockito.when(
                nameSerializerMock.bytesToObj(dummyBytes,
                        RouterNameMgmtConfig.class)).thenThrow(
                new IOException());
        serializer.deserializeName(dummyBytes);
    }

    @Test
    public void testDeserializePeerGoodInput() throws Exception {
        serializer.deserializePeer(dummyBytes);
        Mockito.verify(peerSerializerMock, Mockito.times(1)).bytesToObj(
                dummyBytes, PeerRouterConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializePeerBadInput() throws Exception {
        Mockito.when(
                peerSerializerMock.bytesToObj(dummyBytes,
                        PeerRouterConfig.class)).thenThrow(new IOException());
        serializer.deserializePeer(dummyBytes);
    }

    @Test
    public void testSerializeGoodInput() throws Exception {
        RouterMgmtConfig config = new RouterMgmtConfig("tenantId", "name");
        serializer.serialize(config);
        Mockito.verify(serializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializeBadInput() throws Exception {
        RouterMgmtConfig config = new RouterMgmtConfig("tenantId", "name");
        Mockito.when(serializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }

    @Test
    public void testSerializeNameGoodInput() throws Exception {
        RouterNameMgmtConfig config = new RouterNameMgmtConfig(
                UUID.randomUUID());
        serializer.serialize(config);
        Mockito.verify(nameSerializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializeNameBadInput() throws Exception {
        RouterNameMgmtConfig config = new RouterNameMgmtConfig(
                UUID.randomUUID());
        Mockito.when(nameSerializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }

    @Test
    public void testSerializePeerGoodInput() throws Exception {
        PeerRouterConfig config = new PeerRouterConfig();
        serializer.serialize(config);
        Mockito.verify(peerSerializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializePeerBadInput() throws Exception {
        PeerRouterConfig config = new PeerRouterConfig();
        Mockito.when(peerSerializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }

}
