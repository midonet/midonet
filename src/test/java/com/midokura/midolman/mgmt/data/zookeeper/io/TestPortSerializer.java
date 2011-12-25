/*
 * @(#)TestPortSerializer        1.6 11/11/29
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.io;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Serializer;

public class TestPortSerializer {

    Serializer<PortMgmtConfig> serializerMock = null;
    PortSerializer serializer = null;
    byte[] dummyBytes = { 1, 2, 3 };

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        serializerMock = Mockito.mock(Serializer.class);
        serializer = new PortSerializer(serializerMock);
    }

    @Test
    public void testDeserializeGoodInput() throws Exception {
        serializer.deserialize(dummyBytes);
        Mockito.verify(serializerMock, Mockito.times(1)).bytesToObj(dummyBytes,
                PortMgmtConfig.class);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testDeserializeBadInput() throws Exception {
        Mockito.when(
                serializerMock.bytesToObj(dummyBytes, PortMgmtConfig.class))
                .thenThrow(new IOException());
        serializer.deserialize(dummyBytes);
    }

    @Test
    public void testSerializeGoodInput() throws Exception {
        PortMgmtConfig config = new PortMgmtConfig();
        serializer.serialize(config);
        Mockito.verify(serializerMock, Mockito.times(1)).objToBytes(config);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testSerializeBadInput() throws Exception {
        PortMgmtConfig config = new PortMgmtConfig();
        Mockito.when(serializerMock.objToBytes(config)).thenThrow(
                new IOException());
        serializer.serialize(config);
    }
}
