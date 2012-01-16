/*
 * @(#)TestVifOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.VifSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ZkManager;

public class TestVifOpBuilder {

    private ZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private VifSerializer serializerMock = null;
    private VifOpBuilder builder = null;
    private final static UUID dummyId = UUID.randomUUID();
    private final static VifConfig dummyConfig = new VifConfig();
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };

    @Before
    public void setUp() throws Exception {
        zkDaoMock = Mockito.mock(ZkManager.class);
        pathBuilderMock = Mockito.mock(PathBuilder.class);
        serializerMock = Mockito.mock(VifSerializer.class);
        builder = new VifOpBuilder(zkDaoMock, pathBuilderMock,
                serializerMock);
    }

    @Test
    public void testCreateOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getVifPath(dummyId)).thenReturn(dummyPath);
        Mockito.when(serializerMock.serialize(dummyConfig)).thenReturn(
                dummyBytes);

        builder.getVifCreateOp(dummyId, dummyConfig);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getPersistentCreateOp(
                dummyPath, dummyBytes);
    }

    @Test
    public void testGetVifDeleteOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getVifPath(dummyId)).thenReturn(dummyPath);

        builder.getVifDeleteOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getDeleteOp(dummyPath);
    }

}
