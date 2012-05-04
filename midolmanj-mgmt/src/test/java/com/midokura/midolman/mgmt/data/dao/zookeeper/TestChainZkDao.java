/*
 * @(#)TestChainZkDao        1.6 11/12/25
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Assert;
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

public class TestChainZkDao {

    private ChainZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private ChainSerializer serializerMock = null;
    private ChainZkDao dao = null;
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };
    private final static ChainConfig dummyConfig = new ChainConfig();
    private final static ChainMgmtConfig dummyMgmtConfig = new ChainMgmtConfig();
    private final static ChainNameMgmtConfig dummyNameConfig = new ChainNameMgmtConfig();
    private static Set<String> dummyIds = null;
    static {
        dummyIds = new TreeSet<String>();
        dummyIds.add("foo");
        dummyIds.add("bar");
    }

    @Before
    public void setUp() throws Exception {
        zkDaoMock = Mockito.mock(ChainZkManager.class);
        pathBuilderMock = Mockito.mock(PathBuilder.class);
        serializerMock = Mockito.mock(ChainSerializer.class);
        dao = new ChainZkDao(zkDaoMock, pathBuilderMock, serializerMock);
    }

    @Test
    public void testGetMgmtDataSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(pathBuilderMock.getChainPath(id)).thenReturn(dummyPath);
        Mockito.when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        Mockito.when(serializerMock.deserialize(dummyBytes)).thenReturn(
                dummyMgmtConfig);

        ChainMgmtConfig config = dao.getMgmtData(id);

        Assert.assertEquals(dummyMgmtConfig, config);
    }

    @Test(expected = StateAccessException.class)
    public void testGetMgmtDataDataAccessError() throws Exception {
        Mockito.doThrow(StateAccessException.class).when(zkDaoMock)
                .get(Mockito.anyString());
        dao.getMgmtData(UUID.randomUUID());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetMgmtDataDataBadInput() throws Exception {
        dao.getMgmtData(null);
    }

    @Test
    public void testGetNameDataSuccess() throws Exception {
        String tenantId = "ChainTenant";
        String chainName = "foo";
        Mockito.when(
                pathBuilderMock.getTenantChainNamePath(tenantId, chainName))
                .thenReturn(dummyPath);
        Mockito.when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        Mockito.when(serializerMock.deserializeName(dummyBytes)).thenReturn(
                dummyNameConfig);

        ChainNameMgmtConfig config = dao.getNameData(tenantId, chainName);

        Assert.assertEquals(dummyNameConfig, config);
    }

    @Test(expected = StateAccessException.class)
    public void testGetNameDataDataAccessError() throws Exception {
        Mockito.doThrow(StateAccessException.class).when(zkDaoMock)
                .get(Mockito.anyString());
        dao.getNameData("TenantName", "foo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetNameDataDataBadInput() throws Exception {
        dao.getNameData(null, null);
    }

    @Test
    public void testGetDataSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        Mockito.when(zkDaoMock.get(id)).thenReturn(
                new ZkNodeEntry<UUID, ChainConfig>(id, dummyConfig));

        ChainConfig config = dao.getData(id);

        Assert.assertEquals(dummyConfig, config);
    }

    @Test(expected = StateAccessException.class)
    public void testGetDataDataAccessError() throws Exception {
        Mockito.doThrow(StateAccessException.class).when(zkDaoMock)
                .get(Mockito.any(UUID.class));
        dao.getData(UUID.randomUUID());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetDataDataBadInput() throws Exception {
        dao.getData(null);
    }

    @Test
    public void testGetIdsSuccess() throws Exception {
        String tenantId = "Tenant";
        Mockito.when(
                pathBuilderMock.getTenantChainsPath(tenantId))
                .thenReturn(dummyPath);
        Mockito.when(zkDaoMock.getChildren(dummyPath, null)).thenReturn(
                dummyIds);

        Set<String> ids = dao.getIds(tenantId);

        Assert.assertArrayEquals(dummyIds.toArray(), ids.toArray());
    }

    @Test(expected = StateAccessException.class)
    public void testGetIdsDataAccessError() throws Exception {
        Mockito.doThrow(StateAccessException.class)
                .when(zkDaoMock)
                .getChildren(Mockito.anyString(),
                        (Runnable) Mockito.anyObject());
        dao.getIds("TenantFoo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetIdsBadInput() throws Exception {
        dao.getIds(null);
    }

    @Test
    public void testMultiSuccess() throws Exception {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(dummyPath, dummyBytes, null, CreateMode.PERSISTENT));
        ops.add(Op.delete(dummyPath, -1));
        ops.add(Op.setData(dummyPath, dummyBytes, -1));
        dao.multi(ops);
        Mockito.verify(zkDaoMock, Mockito.times(1)).multi(ops);
    }

    @Test(expected = StateAccessException.class)
    public void testMultiDataAccessError() throws Exception {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(dummyPath, dummyBytes, null, CreateMode.PERSISTENT));
        ops.add(Op.delete(dummyPath, -1));
        ops.add(Op.setData(dummyPath, dummyBytes, -1));
        Mockito.doThrow(StateAccessException.class).when(zkDaoMock).multi(ops);
        dao.multi(ops);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMultiBadInput() throws Exception {
        dao.multi(null);
    }

    @Test
    public void testConstructChainMgmtConfig() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        String name = "foo";
        ChainMgmtConfig config =
                dao.constructChainMgmtConfig(tenantId, name);
        Assert.assertEquals(tenantId, config.tenantId);
        Assert.assertEquals(name, config.name);
    }

    @Test
    public void testConstructChainNameMgmtConfig() throws Exception {
        UUID id = UUID.randomUUID();
        ChainNameMgmtConfig config = dao.constructChainNameMgmtConfig(id);
        Assert.assertEquals(id, config.id);
    }
}
