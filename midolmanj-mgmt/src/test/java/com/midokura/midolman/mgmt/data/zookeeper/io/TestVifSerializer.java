/*
 * @(#)TestVifSerializer        1.6 11/12/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

public class TestVifSerializer {

    Serializer<VifConfig> serializerMock = null;
    VifSerializer serializer = null;
    byte[] dummyBytes = { 1, 2, 3 };

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        serializerMock = Mockito.mock(Serializer.class);
        serializer = new VifSerializer(serializerMock);
    }

    @Test
    public void testDeserializeGoodInput() throws Exception {
        serializer.deserialize(dummyBytes);
        Mockito.verify(serializerMock, Mockito.times(1)).bytesToObj(dummyBytes,
                VifConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializeBadInput() throws Exception {
        Mockito.when(
                serializerMock.bytesToObj(dummyBytes, VifConfig.class))
                .thenThrow(new IOException());
        serializer.deserialize(dummyBytes);
    }

    @Test
    public void testSerializeGoodInput() throws Exception {
        VifConfig config = new VifConfig();
        serializer.serialize(config);
        Mockito.verify(serializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializeBadInput() throws Exception {
        VifConfig config = new VifConfig();
        Mockito.when(serializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }
}
