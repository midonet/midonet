/*
 * @(#)TestPortOpPathBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.PortSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkNodeEntry;

public class TestPortOpPathBuilder {

    private PortZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private PortSerializer serializerMock = null;
    private PortOpPathBuilder builder = null;
    private final static UUID dummyId = UUID.randomUUID();
    private final static PortConfig dummyConfig = null;
    private final static PortMgmtConfig dummyMgmtConfig = new PortMgmtConfig();
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };

    @Before
    public void setUp() throws Exception {
        zkDaoMock = Mockito.mock(PortZkManager.class);
        pathBuilderMock = Mockito.mock(PathBuilder.class);
        serializerMock = Mockito.mock(PortSerializer.class);
        builder = new PortOpPathBuilder(zkDaoMock, pathBuilderMock,
                serializerMock);
    }

    @Test
    public void TestSetDataOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getPortPath(dummyId))
                .thenReturn(dummyPath);
        Mockito.when(serializerMock.serialize(dummyMgmtConfig)).thenReturn(
                dummyBytes);

        builder.getPortSetDataOp(dummyId, dummyMgmtConfig);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getSetDataOp(dummyPath,
                dummyBytes);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void TestLinkCreateOpSuccess() throws Exception {
        // TODO: need a better test.
        builder.getPortLinkCreateOps(dummyId, dummyConfig, dummyId, dummyConfig);
        Mockito.verify(zkDaoMock, Mockito.times(1)).preparePortCreateLink(
                Mockito.any(ZkNodeEntry.class), Mockito.any(ZkNodeEntry.class));
    }

    @Test
    public void TestCreateOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getPortPath(dummyId))
                .thenReturn(dummyPath);
        Mockito.when(serializerMock.serialize(dummyMgmtConfig)).thenReturn(
                dummyBytes);

        builder.getPortCreateOp(dummyId, dummyMgmtConfig);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getPersistentCreateOp(
                dummyPath, dummyBytes);
    }

    @Test
    public void TestGetPortCreateOpsSuccess() throws Exception {
        builder.getPortCreateOps(dummyId, dummyConfig);
        Mockito.verify(zkDaoMock, Mockito.times(1)).preparePortCreate(dummyId,
                dummyConfig);
    }

    @Test
    public void TestGetPortDeleteOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getPortPath(dummyId))
                .thenReturn(dummyPath);

        builder.getPortDeleteOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void TestGetPortDeleteOpsSuccess() throws Exception {
        builder.getPortDeleteOps(dummyId);
        Mockito.verify(zkDaoMock, Mockito.times(1)).preparePortDelete(dummyId);
    }
}
