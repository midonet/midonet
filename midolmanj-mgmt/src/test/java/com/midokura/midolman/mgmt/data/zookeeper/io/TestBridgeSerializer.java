/*
 * @(#)TestBridgeSerializer        1.6 11/11/29
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

public class TestBridgeSerializer {

    Serializer<BridgeMgmtConfig> serializerMock = null;
    Serializer<BridgeNameMgmtConfig> nameSerializerMock = null;
    Serializer<PeerRouterConfig> peerSerializerMock = null;
    BridgeSerializer serializer = null;
    byte[] dummyBytes = { 1, 2, 3 };

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        serializerMock = Mockito.mock(Serializer.class);
        nameSerializerMock = Mockito.mock(Serializer.class);
        peerSerializerMock = Mockito.mock(Serializer.class);
        serializer = new BridgeSerializer(
                serializerMock, nameSerializerMock, peerSerializerMock);
    }

    @Test
    public void testDeserializeGoodInput() throws Exception {
        serializer.deserialize(dummyBytes);
        Mockito.verify(serializerMock, Mockito.times(1)).bytesToObj(dummyBytes,
                BridgeMgmtConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializeBadInput() throws Exception {
        Mockito.when(
                serializerMock.bytesToObj(dummyBytes, BridgeMgmtConfig.class))
                .thenThrow(new IOException());
        serializer.deserialize(dummyBytes);
    }

    @Test
    public void testDeserializeNameGoodInput() throws Exception {
        serializer.deserializeName(dummyBytes);
        Mockito.verify(nameSerializerMock, Mockito.times(1)).bytesToObj(
                dummyBytes, BridgeNameMgmtConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializeNameBadInput() throws Exception {
        Mockito.when(
                nameSerializerMock.bytesToObj(dummyBytes,
                        BridgeNameMgmtConfig.class)).thenThrow(
                new IOException());
        serializer.deserializeName(dummyBytes);
    }

    @Test
    public void testSerializeGoodInput() throws Exception {
        BridgeMgmtConfig config = new BridgeMgmtConfig("tenantId", "name");
        serializer.serialize(config);
        Mockito.verify(serializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializeBadInput() throws Exception {
        BridgeMgmtConfig config = new BridgeMgmtConfig("tenantId", "name");
        Mockito.when(serializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }

    @Test
    public void testSerializeNameGoodInput() throws Exception {
        BridgeNameMgmtConfig config = new BridgeNameMgmtConfig(
                UUID.randomUUID());
        serializer.serialize(config);
        Mockito.verify(nameSerializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializeNameBadInput() throws Exception {
        BridgeNameMgmtConfig config = new BridgeNameMgmtConfig(
                UUID.randomUUID());
        Mockito.when(nameSerializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }

}
