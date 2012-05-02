/*
 * @(#)TestChainOpBuilder        1.6 11/12/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.UUID;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.ChainSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

public class TestChainOpBuilder {

    private ChainZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private ChainSerializer serializerMock = null;
    private ChainOpBuilder builder = null;
    private final static UUID dummyId = UUID.randomUUID();
    private final static UUID dummyRouterId = UUID.randomUUID();
    private final static String dummyChain = "bar";
    private final static ChainConfig dummyConfig = new ChainConfig();
    private final static ChainMgmtConfig dummyMgmtConfig = new ChainMgmtConfig();
    private final static ChainNameMgmtConfig dummyNameConfig = new ChainNameMgmtConfig();
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };

    @Before
    public void setUp() throws Exception {
        zkDaoMock = Mockito.mock(ChainZkManager.class);
        pathBuilderMock = Mockito.mock(PathBuilder.class);
        serializerMock = Mockito.mock(ChainSerializer.class);
        builder = new ChainOpBuilder(zkDaoMock, pathBuilderMock, serializerMock);
    }

    @Test
    public void testGetChainCreateOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getChainPath(dummyId)).thenReturn(
                dummyPath);
        Mockito.when(serializerMock.serialize(dummyMgmtConfig)).thenReturn(
                dummyBytes);

        builder.getChainCreateOp(dummyId, dummyMgmtConfig);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getPersistentCreateOp(
                dummyPath, dummyBytes);
    }

    @Test(expected = ZkStateSerializationException.class)
    public void testGetChainCreateOpSerializationError() throws Exception {
        Mockito.doThrow(ZkStateSerializationException.class)
                .when(serializerMock).serialize(dummyMgmtConfig);
        builder.getChainCreateOp(dummyId, dummyMgmtConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetChainCreateOpBadInputError() throws Exception {
        builder.getChainCreateOp(null, null);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testGetChainCreateOpsSuccess() throws Exception {
        builder.getChainCreateOps(dummyId, dummyConfig);
        Mockito.verify(zkDaoMock, Mockito.times(1)).prepareChainCreate(
                Mockito.any(ZkNodeEntry.class));
    }

    @SuppressWarnings("unchecked")
    @Test(expected = StateAccessException.class)
    public void testGetChainCreateOpsDataAccessError() throws Exception {
        Mockito.doThrow(StateAccessException.class).when(zkDaoMock)
                .prepareChainCreate(Mockito.any(ZkNodeEntry.class));
        builder.getChainCreateOps(dummyId, dummyConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetChainCreateOpsBadInputError() throws Exception {
        builder.getChainCreateOps(null, null);
    }

    @Test
    public void testGetChainDeleteOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getChainPath(dummyId)).thenReturn(
                dummyPath);

        builder.getChainDeleteOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getDeleteOp(dummyPath);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetChainDeleteOpBadInput() throws Exception {
        builder.getChainDeleteOp(null);
    }

    @Test
    public void testGetChainDeleteOpsSuccess() throws Exception {
        builder.getChainDeleteOps(dummyId);
        Mockito.verify(zkDaoMock, Mockito.times(1)).prepareChainDelete(dummyId);
    }

    @Test(expected = StateAccessException.class)
    public void testGetChainDeleteOpsDataAccessError() throws Exception {
        Mockito.doThrow(StateAccessException.class).when(zkDaoMock)
                .prepareChainDelete(dummyId);
        builder.getChainDeleteOps(dummyId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetChainDeleteOpsBadInputError() throws Exception {
        builder.getChainDeleteOps(null);
    }
}
