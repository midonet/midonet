/*
 * @(#)TestVifDaoAdapter        1.6 12/01/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import junit.framework.Assert;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.VifOpService;

public class TestVifDaoAdapter {

    private VifZkDao daoMock = null;
    private VifOpService opServiceMock = null;
    private VifDaoAdapter adapter = null;

    private static List<Op> createTestPersistentCreateOps() {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op
                .create("/foo", new byte[] { 0 }, null, CreateMode.PERSISTENT));
        ops.add(Op
                .create("/bar", new byte[] { 1 }, null, CreateMode.PERSISTENT));
        ops.add(Op
                .create("/baz", new byte[] { 2 }, null, CreateMode.PERSISTENT));
        return ops;
    }

    private static List<Op> createTestDeleteOps() {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete("/foo", -1));
        ops.add(Op.delete("/bar", -1));
        ops.add(Op.delete("/baz", -1));
        return ops;
    }

    private static Set<String> createTestIds(int count) {
        Set<String> ids = new TreeSet<String>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        return ids;
    }

    private static VifConfig createTestVifConfig(UUID portId) {
        VifConfig config = new VifConfig();
        config.portId = portId;
        return config;
    }

    @Before
    public void setUp() throws Exception {
        daoMock = mock(VifZkDao.class);
        opServiceMock = mock(VifOpService.class);
        adapter = spy(new VifDaoAdapter(daoMock, opServiceMock));
    }

    @Test
    public void testCreateWithNoIdSuccess() throws Exception {
        Vif vif = new Vif(null, UUID.randomUUID());
        List<Op> ops = createTestPersistentCreateOps();
        doReturn(ops).when(opServiceMock).buildCreate(any(UUID.class),
                any(VifConfig.class));

        UUID newId = adapter.create(vif);

        Assert.assertEquals(vif.getId(), newId);
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testCreateWithIdSuccess() throws Exception {
        Vif vif = new Vif(UUID.randomUUID(), UUID.randomUUID());
        List<Op> ops = createTestPersistentCreateOps();
        doReturn(ops).when(opServiceMock).buildCreate(any(UUID.class),
                any(VifConfig.class));

        UUID newId = adapter.create(vif);

        Assert.assertEquals(vif.getId(), newId);
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        List<Op> ops = createTestDeleteOps();
        doReturn(ops).when(opServiceMock).buildDelete(id);

        adapter.delete(id);

        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        VifConfig config = createTestVifConfig(UUID.randomUUID());

        doReturn(config).when(daoMock).getData(id);
        doReturn(true).when(daoMock).exists(id);

        Vif vif = adapter.get(id);

        Assert.assertEquals(id, vif.getId());
        Assert.assertEquals(config.portId, vif.getPortId());
    }

    @Test
    public void testListSuccess() throws Exception {

        Set<String> ids = createTestIds(3);

        doReturn(ids).when(daoMock).getIds();
        for (String id : ids) {
            UUID uuid = UUID.fromString(id);
            Vif vif = new Vif(uuid, UUID.randomUUID());
            doReturn(vif).when(adapter).get(uuid);
        }

        List<Vif> vifs = adapter.list();

        Assert.assertEquals(3, vifs.size());
        for (int i = 0; i < vifs.size(); i++) {
            Assert.assertTrue(ids.contains(vifs.get(i).getId().toString()));
        }
    }
}
