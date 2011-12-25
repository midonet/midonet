/*
 * @(#)TestChainSerializer        1.6 11/11/29
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

public class TestChainSerializer {

    Serializer<ChainMgmtConfig> serializerMock = null;
    Serializer<ChainNameMgmtConfig> nameSerializerMock = null;
    ChainSerializer serializer = null;
    byte[] dummyBytes = { 1, 2, 3 };

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        serializerMock = Mockito.mock(Serializer.class);
        nameSerializerMock = Mockito.mock(Serializer.class);
        serializer = new ChainSerializer(serializerMock, nameSerializerMock);
    }

    @Test
    public void testDeserializeGoodInput() throws Exception {
        serializer.deserialize(dummyBytes);
        Mockito.verify(serializerMock, Mockito.times(1)).bytesToObj(dummyBytes,
                ChainMgmtConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializeBadInput() throws Exception {
        Mockito.when(
                serializerMock.bytesToObj(dummyBytes, ChainMgmtConfig.class))
                .thenThrow(new IOException());
        serializer.deserialize(dummyBytes);
    }

    @Test
    public void testDeserializeNameGoodInput() throws Exception {
        serializer.deserializeName(dummyBytes);
        Mockito.verify(nameSerializerMock, Mockito.times(1)).bytesToObj(
                dummyBytes, ChainNameMgmtConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializeNameBadInput() throws Exception {
        Mockito.when(
                nameSerializerMock.bytesToObj(dummyBytes,
                        ChainNameMgmtConfig.class))
                .thenThrow(new IOException());
        serializer.deserializeName(dummyBytes);
    }

    @Test
    public void testSerializeGoodInput() throws Exception {
        ChainMgmtConfig config = new ChainMgmtConfig(ChainTable.NAT.toString());
        serializer.serialize(config);
        Mockito.verify(serializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializeBadInput() throws Exception {
        ChainMgmtConfig config = new ChainMgmtConfig(ChainTable.NAT.toString());
        Mockito.when(serializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }

    @Test
    public void testSerializeNameGoodInput() throws Exception {
        ChainNameMgmtConfig config = new ChainNameMgmtConfig(UUID.randomUUID());
        serializer.serialize(config);
        Mockito.verify(nameSerializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializeNameBadInput() throws Exception {
        ChainNameMgmtConfig config = new ChainNameMgmtConfig(UUID.randomUUID());
        Mockito.when(nameSerializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }

}
