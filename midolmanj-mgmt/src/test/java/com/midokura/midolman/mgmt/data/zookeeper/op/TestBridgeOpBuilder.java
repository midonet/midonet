/*
 * @(#)TestBridgeOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.BridgeSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.ZkNodeEntry;

public class TestBridgeOpBuilder {

    private BridgeZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private BridgeSerializer serializerMock = null;
    private BridgeOpBuilder builder = null;
    private final static UUID dummyId = UUID.randomUUID();
    private final static String dummyTenantId = "foo";
    private final static String dummyBridgeName = "bar";
    private final static BridgeConfig dummyConfig = null;
    private final static BridgeMgmtConfig dummyMgmtConfig = new BridgeMgmtConfig();
    private final static BridgeNameMgmtConfig dummyNameMgmtConfig = new BridgeNameMgmtConfig();
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };

    @Before
    public void setUp() throws Exception {
        zkDaoMock = mock(BridgeZkManager.class);
        pathBuilderMock = mock(PathBuilder.class);
        serializerMock = mock(BridgeSerializer.class);
        builder = new BridgeOpBuilder(zkDaoMock, pathBuilderMock,
                serializerMock);
    }

    @Test
    public void TestCreateOpSuccess() throws Exception {
        when(pathBuilderMock.getBridgePath(dummyId)).thenReturn(dummyPath);
        when(serializerMock.serialize(dummyMgmtConfig)).thenReturn(dummyBytes);

        builder.getBridgeCreateOp(dummyId, dummyMgmtConfig);

        verify(zkDaoMock, times(1))
                .getPersistentCreateOp(dummyPath, dummyBytes);
    }

    @Test
    public void TestGetBridgeCreateOpsSuccess() throws Exception {
        builder.getBridgeCreateOps(dummyId, dummyConfig);
        verify(zkDaoMock, times(1)).prepareBridgeCreate(dummyId, dummyConfig);
    }

    @Test
    public void TestGetBridgeDeleteOpSuccess() throws Exception {
        when(pathBuilderMock.getBridgePath(dummyId)).thenReturn(dummyPath);

        builder.getBridgeDeleteOp(dummyId);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void TestGetBridgeDeleteOpsSuccess() throws Exception {
        builder.getBridgeDeleteOps(dummyId);
        verify(zkDaoMock, times(1)).prepareBridgeDelete(any(ZkNodeEntry.class));
    }

    @Test
    public void TestSetDataOpSuccess() throws Exception {
        when(pathBuilderMock.getBridgePath(dummyId)).thenReturn(dummyPath);
        when(serializerMock.serialize(dummyMgmtConfig)).thenReturn(dummyBytes);

        builder.getBridgeSetDataOp(dummyId, dummyMgmtConfig);

        verify(zkDaoMock, times(1)).getSetDataOp(dummyPath, dummyBytes);
    }

    @Test
    public void TestCreateTenantBridgeOpSuccess() throws Exception {
        when(pathBuilderMock.getTenantBridgePath(dummyTenantId, dummyId))
                .thenReturn(dummyPath);

        builder.getTenantBridgeCreateOp(dummyTenantId, dummyId);

        verify(zkDaoMock, times(1))
                .getPersistentCreateOp(dummyPath, null);
    }

    @Test
    public void TestDeleteTenantBridgeOpSuccess() throws Exception {
        when(pathBuilderMock.getTenantBridgePath(dummyTenantId, dummyId))
                .thenReturn(dummyPath);

        builder.getTenantBridgeDeleteOp(dummyTenantId, dummyId);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void TestCreateTenantBridgeNameOpSuccess() throws Exception {
        when(
                pathBuilderMock.getTenantBridgeNamePath(dummyTenantId,
                        dummyBridgeName)).thenReturn(dummyPath);
        when(serializerMock.serialize(dummyNameMgmtConfig)).thenReturn(
                dummyBytes);

        builder.getTenantBridgeNameCreateOp(dummyTenantId, dummyBridgeName,
                dummyNameMgmtConfig);

        verify(zkDaoMock, times(1))
                .getPersistentCreateOp(dummyPath, dummyBytes);
    }

    @Test
    public void TestDeleteTenantBridgeNameOpSuccess() throws Exception {
        when(
                pathBuilderMock.getTenantBridgeNamePath(dummyTenantId,
                        dummyBridgeName)).thenReturn(dummyPath);

        builder.getTenantBridgeNameDeleteOp(dummyTenantId, dummyBridgeName);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

}
