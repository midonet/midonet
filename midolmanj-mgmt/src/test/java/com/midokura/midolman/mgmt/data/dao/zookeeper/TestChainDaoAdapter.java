/*
 * @(#)TestChainDaoAdapter        1.6 12/01/8
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

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

import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.ChainOpService;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class TestChainDaoAdapter {

    private ChainZkDao daoMock = null;
    private RuleDao ruleDaoMock = null;
    private ChainOpService opServiceMock = null;
    private ChainDaoAdapter adapter = null;

    @Before
    public void setUp() throws Exception {
        daoMock = mock(ChainZkDao.class);
        opServiceMock = mock(ChainOpService.class);
        ruleDaoMock = mock(RuleDao.class);
        adapter = new ChainDaoAdapter(daoMock, opServiceMock, ruleDaoMock);
    }

    private static Chain createTestNatChain(UUID id) {
        Chain chain = new Chain();
        chain.setId(id);
        chain.setName("foo");
        chain.setRouterId(UUID.randomUUID());
        chain.setTable(DtoRuleChain.ChainTable.NAT);
        return chain;
    }

    private static ChainConfig createTestChainConfig(String name, UUID routerId) {
        ChainConfig config = new ChainConfig();
        config.name = name;
        config.routerId = routerId;
        return config;
    }

    private static ChainMgmtConfig createTestChainMgmtConfig(ChainTable table) {
        ChainMgmtConfig config = new ChainMgmtConfig();
        config.table = table;
        return config;
    }

    private static ChainNameMgmtConfig createTestChainNameMgmtConfig(UUID id) {
        ChainNameMgmtConfig config = new ChainNameMgmtConfig();
        config.id = id;
        return config;
    }

    private static Rule createTestRule(UUID id, UUID chainId) {
        Rule rule = new Rule();
        rule.setId(id);
        rule.setChainId(chainId);
        return rule;
    }

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

    @Test
    public void testCreateNoIdSuccess() throws Exception {
        Chain chain = createTestNatChain(null);
        List<Op> ops = createTestPersistentCreateOps();
        when(
                opServiceMock.buildCreate(any(UUID.class),
                        any(ChainConfig.class), any(ChainMgmtConfig.class),
                        any(ChainNameMgmtConfig.class))).thenReturn(ops);

        UUID newId = adapter.create(chain);

        Assert.assertEquals(newId, chain.getId());
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testCreateWithIdSuccess() throws Exception {
        Chain chain = createTestNatChain(UUID.randomUUID());
        List<Op> ops = createTestPersistentCreateOps();
        when(
                opServiceMock.buildCreate(any(UUID.class),
                        any(ChainConfig.class), any(ChainMgmtConfig.class),
                        any(ChainNameMgmtConfig.class))).thenReturn(ops);

        UUID newId = adapter.create(chain);

        Assert.assertEquals(newId, chain.getId());
        verify(daoMock, times(1)).multi(ops);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateWithBuiltInChainName() throws Exception {
        Chain chain = createTestNatChain(UUID.randomUUID());
        chain.setName(ChainTable.getBuiltInChainNames(ChainTable.NAT)[0]);
        adapter.create(chain);
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = createTestChainConfig("foo", UUID.randomUUID());
        ChainMgmtConfig mgmtConfig = createTestChainMgmtConfig(ChainTable.NAT);

        List<Op> ops = createTestDeleteOps();

        when(daoMock.getData(id)).thenReturn(config);
        when(daoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(daoMock.exists(id)).thenReturn(true);

        when(opServiceMock.buildDelete(id, true)).thenReturn(ops);
        adapter.delete(id);

        verify(daoMock, times(1)).multi(ops);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDeleteBuiltInChain() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = createTestChainConfig("foo", UUID.randomUUID());
        ChainMgmtConfig mgmtConfig = createTestChainMgmtConfig(ChainTable.NAT);
        config.name = ChainTable.getBuiltInChainNames(ChainTable.NAT)[0];

        when(daoMock.getData(id)).thenReturn(config);
        when(daoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(daoMock.exists(id)).thenReturn(true);

        adapter.delete(id);
    }

    @Test
    public void testGenerateBuiltInChainsSuccess() throws Exception {
        UUID routerId = UUID.randomUUID();

        List<Chain> chains = adapter.generateBuiltInChains(routerId);

        for (Chain chain : chains) {
            Assert.assertTrue(ChainTable.isBuiltInChainName(chain.getTable(),
                    chain.getName()));
        }
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = createTestChainConfig("foo", UUID.randomUUID());
        ChainMgmtConfig mgmtConfig = createTestChainMgmtConfig(ChainTable.NAT);

        when(daoMock.getData(id)).thenReturn(config);
        when(daoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(daoMock.exists(id)).thenReturn(true);

        Chain chain = adapter.get(id);

        Assert.assertEquals(id, chain.getId());
        Assert.assertEquals(config.routerId, chain.getRouterId());
        Assert.assertEquals(config.name, chain.getName());
        Assert.assertEquals(Enum.valueOf(DtoRuleChain.ChainTable.class,
                mgmtConfig.table.name()), chain.getTable());
    }

    @Test
    public void testGetByTableSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = createTestChainConfig("foo", UUID.randomUUID());
        ChainMgmtConfig mgmtConfig = createTestChainMgmtConfig(ChainTable.NAT);
        ChainNameMgmtConfig nameConfig = createTestChainNameMgmtConfig(id);

        when(daoMock.getData(id)).thenReturn(config);
        when(daoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(
                daoMock.getNameData(config.routerId, mgmtConfig.table,
                        config.name)).thenReturn(nameConfig);
        when(daoMock.exists(id)).thenReturn(true);

        Chain chain = adapter.get(config.routerId, mgmtConfig.table,
                config.name);

        Assert.assertEquals(id, chain.getId());
        Assert.assertEquals(config.routerId, chain.getRouterId());
        Assert.assertEquals(config.name, chain.getName());
        Assert.assertEquals(Enum.valueOf(DtoRuleChain.ChainTable.class,
                mgmtConfig.table.name()), chain.getTable());
    }

    @Test
    public void testGetByRuleSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = createTestChainConfig("foo", UUID.randomUUID());
        ChainMgmtConfig mgmtConfig = createTestChainMgmtConfig(ChainTable.NAT);
        Rule rule = createTestRule(UUID.randomUUID(), id);

        when(daoMock.exists(id)).thenReturn(true);
        when(daoMock.getData(id)).thenReturn(config);
        when(daoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(ruleDaoMock.get(rule.getId())).thenReturn(rule);

        Chain chain = adapter.getByRule(rule.getId());

        Assert.assertEquals(id, chain.getId());
        Assert.assertEquals(config.routerId, chain.getRouterId());
        Assert.assertEquals(config.name, chain.getName());
        Assert.assertEquals(Enum.valueOf(DtoRuleChain.ChainTable.class,
                mgmtConfig.table.name()), chain.getTable());
    }

    @Test
    public void testListSuccess() throws Exception {
        UUID routerId = UUID.randomUUID();
        ChainConfig config = createTestChainConfig("foo", routerId);
        ChainMgmtConfig mgmtConfig = createTestChainMgmtConfig(ChainTable.NAT);

        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {
            Set<String> ids = createTestIds(3);
            when(daoMock.getIds(routerId, chainTable)).thenReturn(ids);
        }
        when(daoMock.getData(any(UUID.class))).thenReturn(config);
        when(daoMock.getMgmtData(any(UUID.class))).thenReturn(mgmtConfig);

        List<Chain> chains = adapter.list(routerId);

        int builtInChainNum = ChainTable.values().length;
        Assert.assertEquals(builtInChainNum * 3, chains.size());
    }

    @Test
    public void testListByTableSuccess() throws Exception {
        UUID routerId = UUID.randomUUID();
        Set<String> ids = createTestIds(3);
        ChainConfig config = createTestChainConfig("foo", routerId);
        ChainMgmtConfig mgmtConfig = createTestChainMgmtConfig(ChainTable.NAT);

        when(daoMock.getIds(routerId, ChainTable.NAT)).thenReturn(ids);
        when(daoMock.getData(any(UUID.class))).thenReturn(config);
        when(daoMock.getMgmtData(any(UUID.class))).thenReturn(mgmtConfig);
        when(daoMock.exists(any(UUID.class))).thenReturn(true);

        List<Chain> chains = adapter.list(routerId, ChainTable.NAT);

        Assert.assertEquals(3, chains.size());
        for (Chain chain : chains) {
            Assert.assertTrue(ids.contains(chain.getId().toString()));
        }
    }

}
