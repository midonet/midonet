/*
 * @(#)TestRuleZkProxy        1.6 12/01/8
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.ZkNodeEntry;

public class TestRuleZkProxy {

    private RuleZkManager daoMock = null;
    private RuleZkProxy proxy = null;

    @Before
    public void setUp() throws Exception {
        daoMock = mock(RuleZkManager.class);
        proxy = new RuleZkProxy(daoMock);
    }

    private com.midokura.midolman.rules.Rule getRuleConfig(UUID chainId,
            int position) {
        Condition c = new Condition();
        return new com.midokura.midolman.rules.LiteralRule(c, Action.ACCEPT,
                chainId, position);
    }

    private ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule> getZkNodeEntry(
            UUID id, UUID chainId, int position) {
        com.midokura.midolman.rules.Rule config = getRuleConfig(chainId,
                position);
        return new ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule>(id,
                config);
    }

    @Test
    public void testCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        com.midokura.midolman.rules.Rule config = getRuleConfig(
                UUID.randomUUID(), 1);
        Rule rule = mock(Rule.class);
        when(rule.toZkRule()).thenReturn(config);
        when(daoMock.create(config)).thenReturn(id);

        UUID newId = proxy.create(rule);

        Assert.assertEquals(id, newId);
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule> node = getZkNodeEntry(
                UUID.randomUUID(), UUID.randomUUID(), 1);

        when(daoMock.get(id)).thenReturn(node);

        Rule rule = proxy.get(id);

        Assert.assertEquals(id, rule.getId());
    }

    @Test
    public void testListSuccess() throws Exception {

        UUID chainId = UUID.randomUUID();
        List<ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule>> entries = new ArrayList<ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule>>();
        entries.add(getZkNodeEntry(UUID.randomUUID(), UUID.randomUUID(), 1));
        entries.add(getZkNodeEntry(UUID.randomUUID(), UUID.randomUUID(), 2));
        entries.add(getZkNodeEntry(UUID.randomUUID(), UUID.randomUUID(), 3));

        when(daoMock.list(chainId)).thenReturn(entries);

        List<Rule> rules = proxy.list(chainId);

        Assert.assertEquals(3, rules.size());
        Assert.assertEquals(entries.size(), rules.size());
        Assert.assertEquals(entries.remove(0).key, rules.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, rules.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, rules.remove(0).getId());
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        proxy.delete(id);

        verify(daoMock, times(1)).delete(id);
    }
}
