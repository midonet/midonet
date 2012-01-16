/*
 * @(#)TestRouterOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.RouterSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.RouterZkManager;

public class TestRouterOpBuilder {

    private RouterZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private RouterSerializer serializerMock = null;
    private RouterOpBuilder builder = null;
    private final static UUID dummyId = UUID.randomUUID();
    private final static UUID dummyPeerId = UUID.randomUUID();
    private final static String dummyTenantId = "foo";
    private final static String dummyRouterName = "bar";
    private final static RouterMgmtConfig dummyMgmtConfig = new RouterMgmtConfig();
    private final static RouterNameMgmtConfig dummyNameMgmtConfig = new RouterNameMgmtConfig();
    private final static PeerRouterConfig dummyPeerConfig = new PeerRouterConfig();
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };

    @Before
    public void setUp() throws Exception {
        zkDaoMock = mock(RouterZkManager.class);
        pathBuilderMock = mock(PathBuilder.class);
        serializerMock = mock(RouterSerializer.class);
        builder = new RouterOpBuilder(zkDaoMock, pathBuilderMock,
                serializerMock);
    }

    @Test
    public void testCreateOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterPath(dummyId)).thenReturn(dummyPath);
        when(serializerMock.serialize(dummyMgmtConfig)).thenReturn(dummyBytes);

        builder.getRouterCreateOp(dummyId, dummyMgmtConfig);

        verify(zkDaoMock, times(1))
                .getPersistentCreateOp(dummyPath, dummyBytes);
    }

    @Test
    public void testGetRouterCreateOpsSuccess() throws Exception {
        builder.getRouterCreateOps(dummyId);
        verify(zkDaoMock, times(1)).prepareRouterCreate(dummyId);
    }

    @Test
    public void testGetRouterDeleteOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterPath(dummyId)).thenReturn(dummyPath);

        builder.getRouterDeleteOp(dummyId);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void testGetRouterDeleteOpsSuccess() throws Exception {
        builder.getRouterDeleteOps(dummyId);
        verify(zkDaoMock, times(1)).prepareRouterDelete(dummyId);
    }

    @Test
    public void testCreateRouterLinkOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterRouterPath(dummyId, dummyPeerId))
                .thenReturn(dummyPath);

        builder.getRouterRouterCreateOp(dummyId, dummyPeerId, dummyPeerConfig);

        verify(zkDaoMock, times(1)).getPersistentCreateOp(dummyPath, null);
    }

    @Test
    public void testDeleteRouterLinkOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterRouterPath(dummyId, dummyPeerId))
                .thenReturn(dummyPath);

        builder.getRouterRouterDeleteOp(dummyId, dummyPeerId);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void testCreateRouterLinksOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterRoutersPath(dummyId)).thenReturn(
                dummyPath);

        builder.getRouterRoutersCreateOp(dummyId);

        verify(zkDaoMock, times(1)).getPersistentCreateOp(dummyPath, null);
    }

    @Test
    public void testDeleteRouterLinksOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterRoutersPath(dummyId)).thenReturn(
                dummyPath);

        builder.getRouterRoutersDeleteOp(dummyId);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void testSetDataOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterPath(dummyId)).thenReturn(dummyPath);
        when(serializerMock.serialize(dummyMgmtConfig)).thenReturn(dummyBytes);

        builder.getRouterSetDataOp(dummyId, dummyMgmtConfig);

        verify(zkDaoMock, times(1)).getSetDataOp(dummyPath, dummyBytes);
    }

    @Test
    public void testCreateTableChainNamesOpSuccess() throws Exception {
        when(
                pathBuilderMock.getRouterTableChainNamesPath(dummyId,
                        ChainTable.NAT)).thenReturn(dummyPath);

        builder.getRouterTableChainNamesCreateOp(dummyId, ChainTable.NAT);

        verify(zkDaoMock, times(1)).getPersistentCreateOp(dummyPath, null);
    }

    @Test
    public void testDeleteTableChainNamesOpSuccess() throws Exception {
        when(
                pathBuilderMock.getRouterTableChainNamesPath(dummyId,
                        ChainTable.NAT)).thenReturn(dummyPath);

        builder.getRouterTableChainNamesDeleteOp(dummyId, ChainTable.NAT);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void testCreateTableChainsOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterTableChainsPath(dummyId, ChainTable.NAT))
                .thenReturn(dummyPath);

        builder.getRouterTableChainsCreateOp(dummyId, ChainTable.NAT);

        verify(zkDaoMock, times(1)).getPersistentCreateOp(dummyPath, null);
    }

    @Test
    public void testDeleteTableChainsOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterTableChainsPath(dummyId, ChainTable.NAT))
                .thenReturn(dummyPath);

        builder.getRouterTableChainsDeleteOp(dummyId, ChainTable.NAT);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void testCreateTableOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterTablePath(dummyId, ChainTable.NAT))
                .thenReturn(dummyPath);

        builder.getRouterTableCreateOp(dummyId, ChainTable.NAT);

        verify(zkDaoMock, times(1)).getPersistentCreateOp(dummyPath, null);
    }

    @Test
    public void testDeleteTableOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterTablePath(dummyId, ChainTable.NAT))
                .thenReturn(dummyPath);

        builder.getRouterTableDeleteOp(dummyId, ChainTable.NAT);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void testCreateTablesOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterTablesPath(dummyId))
                .thenReturn(dummyPath);

        builder.getRouterTablesCreateOp(dummyId);

        verify(zkDaoMock, times(1)).getPersistentCreateOp(dummyPath, null);
    }

    @Test
    public void testDeleteTablesOpSuccess() throws Exception {
        when(pathBuilderMock.getRouterTablesPath(dummyId))
                .thenReturn(dummyPath);

        builder.getRouterTablesDeleteOp(dummyId);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void testCreateTenantRouterOpSuccess() throws Exception {
        when(pathBuilderMock.getTenantRouterPath(dummyTenantId, dummyId))
                .thenReturn(dummyPath);

        builder.getTenantRouterCreateOp(dummyTenantId, dummyId);

        verify(zkDaoMock, times(1)).getPersistentCreateOp(dummyPath, null);
    }

    @Test
    public void testDeleteTenantRouterOpSuccess() throws Exception {
        when(pathBuilderMock.getTenantRouterPath(dummyTenantId, dummyId))
                .thenReturn(dummyPath);

        builder.getTenantRouterDeleteOp(dummyTenantId, dummyId);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void testCreateTenantRouterNameOpSuccess() throws Exception {
        when(
                pathBuilderMock.getTenantRouterNamePath(dummyTenantId,
                        dummyRouterName)).thenReturn(dummyPath);
        when(serializerMock.serialize(dummyNameMgmtConfig)).thenReturn(
                dummyBytes);

        builder.getTenantRouterNameCreateOp(dummyTenantId, dummyRouterName,
                dummyNameMgmtConfig);

        verify(zkDaoMock, times(1))
                .getPersistentCreateOp(dummyPath, dummyBytes);
    }

    @Test
    public void testDeleteTenantRouterNameOpSuccess() throws Exception {
        when(
                pathBuilderMock.getTenantRouterNamePath(dummyTenantId,
                        dummyRouterName)).thenReturn(dummyPath);

        builder.getTenantRouterNameDeleteOp(dummyTenantId, dummyRouterName);

        verify(zkDaoMock, times(1)).getDeleteOp(dummyPath);
    }

}
