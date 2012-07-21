/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.util.UUID;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.zookeeper.dao.PortDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.PortInUseException;
import com.midokura.midolman.state.PortZkManager;

@RunWith(MockitoJUnitRunner.class)
public class TestPortDaoImpl {

    private PortDaoImpl testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PortZkManager dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private BgpDao bgpDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private VpnDao vpnDao;

    private static Port createTestMaterializedPort(UUID id, UUID routerId,
            UUID vifId) {
        MaterializedRouterPort port = new MaterializedRouterPort();
        port.setId(id);
        port.setDeviceId(routerId);
        port.setVifId(vifId);
        port.setLocalNetworkAddress("192.168.100.2");
        port.setLocalNetworkLength(24);
        port.setNetworkAddress("192.168.100.0");
        port.setNetworkLength(24);
        port.setPortAddress("192.168.100.1");
        return port;
    }

    private static Port createTestLogicalPort(UUID id, UUID routerId,
            UUID peerId, UUID peerRouterId) {
        LogicalRouterPort port = new LogicalRouterPort();
        port.setId(id);
        port.setDeviceId(routerId);
        port.setNetworkAddress("192.168.100.0");
        port.setNetworkLength(24);
        port.setPeerId(peerId);
        port.setPortAddress("192.168.200.1");
        return port;
    }

    @Before
    public void setUp() throws Exception {
        testObject = spy(new PortDaoImpl(dao, bgpDao, vpnDao));
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(dao).exists(id);

        Port port = testObject.get(id);

        Assert.assertNull(port);
    }

    @Test(expected = PortInUseException.class)
    public void testDeleteWithVifPluggedError() throws Exception {

        Port port = createTestMaterializedPort(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        doReturn(port).when(testObject).get(port.getId());

        testObject.delete(port.getId());
    }

    @Test(expected = PortInUseException.class)
    public void testDeleteLogicalPortError() throws Exception {

        Port port = createTestLogicalPort(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        doReturn(port).when(testObject).get(port.getId());

        testObject.delete(port.getId());
    }
}
