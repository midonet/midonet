/*
 * @(#)TestVpnZkProxy        1.6 12/01/8
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

import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.VpnZkManager;
import com.midokura.midolman.state.VpnZkManager.VpnConfig;
import com.midokura.midolman.state.VpnZkManager.VpnType;
import com.midokura.midolman.state.ZkNodeEntry;

public class TestVpnZkProxy {

    private VpnZkManager daoMock = null;
    private VpnZkProxy proxy = null;

    @Before
    public void setUp() throws Exception {
        daoMock = mock(VpnZkManager.class);
        proxy = new VpnZkProxy(daoMock);
    }

    private VpnConfig getVpnConfig(UUID publicPortId, UUID privatePortId,
            String remoteIp, VpnType type, int port) {
        return new VpnConfig(publicPortId, privatePortId, remoteIp, type, port);
    }

    private ZkNodeEntry<UUID, VpnConfig> getZkNodeEntry(UUID id,
            UUID publicPortId, UUID privatePortId, String remoteIp,
            VpnType type, int port) {
        VpnConfig config = getVpnConfig(publicPortId, privatePortId, remoteIp,
                type, port);
        return new ZkNodeEntry<UUID, VpnConfig>(id, config);
    }

    @Test
    public void testCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        VpnConfig config = getVpnConfig(UUID.randomUUID(), UUID.randomUUID(),
                "14.0.0.1", VpnType.OPENVPN_CLIENT, 1);
        Vpn vpn = mock(Vpn.class);
        when(vpn.toConfig()).thenReturn(config);
        when(daoMock.create(config)).thenReturn(id);

        UUID newId = proxy.create(vpn);

        Assert.assertEquals(id, newId);
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, VpnConfig> node = getZkNodeEntry(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "14.0.0.1",
                VpnType.OPENVPN_CLIENT, 1);

        when(daoMock.get(id)).thenReturn(node);

        Vpn vpn = proxy.get(id);

        Assert.assertEquals(id, vpn.getId());
    }

    @Test
    public void testListSuccess() throws Exception {

        UUID portId = UUID.randomUUID();
        List<ZkNodeEntry<UUID, VpnConfig>> entries = new ArrayList<ZkNodeEntry<UUID, VpnConfig>>();
        entries.add(getZkNodeEntry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "14.0.0.1", VpnType.OPENVPN_CLIENT, 1));
        entries.add(getZkNodeEntry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "14.0.0.2", VpnType.OPENVPN_SERVER, 2));
        entries.add(getZkNodeEntry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "14.0.0.3", VpnType.OPENVPN_TCP_CLIENT, 3));

        when(daoMock.list(portId)).thenReturn(entries);

        List<Vpn> vpns = proxy.list(portId);

        Assert.assertEquals(3, vpns.size());
        Assert.assertEquals(entries.size(), vpns.size());
        Assert.assertEquals(entries.remove(0).key, vpns.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, vpns.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, vpns.remove(0).getId());
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        proxy.delete(id);

        verify(daoMock, times(1)).delete(id);
    }
}
