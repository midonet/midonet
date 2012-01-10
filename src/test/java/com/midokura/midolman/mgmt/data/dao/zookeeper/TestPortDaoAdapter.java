/*
 * @(#)TestPortDaoAdapter        1.6 12/01/10
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
import static org.mockito.Mockito.when;

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

import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.PortOpService;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

public class TestPortDaoAdapter {

    private PortZkDao daoMock = null;
    private BgpDao bgpDaoMock = null;
    private VpnDao vpnDaoMock = null;
    private PortOpService opServiceMock = null;
    private PortDaoAdapter adapter = null;

    @Before
    public void setUp() throws Exception {
        daoMock = mock(PortZkDao.class);
        opServiceMock = mock(PortOpService.class);
        bgpDaoMock = mock(BgpDao.class);
        vpnDaoMock = mock(VpnDao.class);
        adapter = spy(new PortDaoAdapter(daoMock, opServiceMock, bgpDaoMock,
                vpnDaoMock));
    }

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
        port.setPeerPortAddress("196.168.200.2");
        port.setPeerRouterId(peerRouterId);
        port.setPortAddress("192.168.200.1");
        return port;
    }

    private static Port createTestBridgePort(UUID id, UUID bridgeId) {
        BridgePort port = new BridgePort();
        port.setId(id);
        port.setDeviceId(bridgeId);
        return port;
    }

    private static PortMgmtConfig createTestMgmtConfig(UUID vifId) {
        PortMgmtConfig config = new PortMgmtConfig();
        config.vifId = vifId;
        return config;
    }

    private static PortConfig createTestLogicalRouterPortConfig(UUID routerId,
            UUID peerId) {
        LogicalRouterPortConfig config = new LogicalRouterPortConfig();
        config.device_id = routerId;
        config.nwAddr = 167772162;
        config.nwLength = 24;
        config.portAddr = 167772163;
        config.peer_uuid = peerId;
        return config;
    }

    private static PortConfig createTestMaterializedRouterPortConfig(
            UUID routerId) {
        MaterializedRouterPortConfig config = new MaterializedRouterPortConfig();
        config.device_id = routerId;
        config.nwAddr = 167772162;
        config.nwLength = 24;
        config.portAddr = 167772163;
        return config;
    }

    private static Bgp createTestBgp(UUID id, UUID portId) {
        Bgp bgp = new Bgp();
        bgp.setId(id);
        bgp.setPortId(portId);
        return bgp;
    }

    private static Vpn createTestVpn(UUID id, UUID publicPortId) {
        Vpn vpn = new Vpn();
        vpn.setId(id);
        vpn.setPublicPortId(publicPortId);
        return vpn;
    }

    private static PortConfig createTestBridgePortConfig(UUID bridgeId) {
        return new BridgePortConfig(bridgeId);
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

    private static Set<UUID> createTestIds(int count) {
        Set<UUID> ids = new TreeSet<UUID>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    @Test
    public void testCreateSuccess() throws Exception {
        Port port = createTestMaterializedPort(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        List<Op> ops = createTestPersistentCreateOps();
        when(
                opServiceMock.buildCreate(any(UUID.class),
                        any(PortConfig.class), any(PortMgmtConfig.class)))
                .thenReturn(ops);

        UUID newId = adapter.create(port);

        Assert.assertEquals(newId, port.getId());
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testDeleteSuccess() throws Exception {

        Port port = createTestMaterializedPort(UUID.randomUUID(),
                UUID.randomUUID(), null);
        List<Op> ops = createTestDeleteOps();

        doReturn(port).when(adapter).get(port.getId());
        doReturn(ops).when(opServiceMock).buildDelete(port.getId(), true);

        adapter.delete(port.getId());

        verify(daoMock, times(1)).multi(ops);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDeleteWithVifPluggedError() throws Exception {

        Port port = createTestMaterializedPort(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        doReturn(port).when(adapter).get(port.getId());

        adapter.delete(port.getId());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testDeleteLogicalPortError() throws Exception {

        Port port = createTestLogicalPort(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        doReturn(port).when(adapter).get(port.getId());

        adapter.delete(port.getId());
    }

    @Test
    public void testExistsTrueSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(daoMock).exists(id);
        boolean exists = adapter.exists(id);
        Assert.assertTrue(exists);
    }

    @Test
    public void testExistsFalseSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(daoMock).exists(id);
        boolean exists = adapter.exists(id);
        Assert.assertFalse(exists);
    }

    @Test
    public void testGetLogicalRouterPortSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortConfig config = createTestLogicalRouterPortConfig(
                UUID.randomUUID(), UUID.randomUUID());
        doReturn(config).when(daoMock).getData(id);

        Port port = adapter.get(id);

        Assert.assertEquals(LogicalRouterPort.class, port.getClass());
        Assert.assertEquals(id, port.getId());
        Assert.assertEquals(config.device_id, port.getDeviceId());
    }

    @Test
    public void testGetMaterializedRouterPortSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortConfig config = createTestMaterializedRouterPortConfig(UUID
                .randomUUID());
        PortMgmtConfig mgmtConfig = createTestMgmtConfig(UUID.randomUUID());
        doReturn(config).when(daoMock).getData(id);
        doReturn(mgmtConfig).when(daoMock).getMgmtData(id);

        Port port = adapter.get(id);

        Assert.assertEquals(MaterializedRouterPort.class, port.getClass());
        Assert.assertEquals(id, port.getId());
        Assert.assertEquals(config.device_id, port.getDeviceId());
        Assert.assertEquals(mgmtConfig.vifId, port.getVifId());
    }

    @Test
    public void testGetBridgePortSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortConfig config = createTestBridgePortConfig(UUID.randomUUID());
        PortMgmtConfig mgmtConfig = createTestMgmtConfig(UUID.randomUUID());
        doReturn(config).when(daoMock).getData(id);
        doReturn(mgmtConfig).when(daoMock).getMgmtData(id);

        Port port = adapter.get(id);

        Assert.assertEquals(BridgePort.class, port.getClass());
        Assert.assertEquals(id, port.getId());
        Assert.assertEquals(config.device_id, port.getDeviceId());
        Assert.assertEquals(mgmtConfig.vifId, port.getVifId());
    }

    @Test
    public void testGetByAdRouteSuccess() throws Exception {
        UUID adRouteId = UUID.randomUUID();
        Bgp bgp = createTestBgp(UUID.randomUUID(), UUID.randomUUID());
        Port port = createTestMaterializedPort(UUID.randomUUID(),
                UUID.randomUUID(), null);
        doReturn(bgp).when(bgpDaoMock).getByAdRoute(adRouteId);
        doReturn(port).when(adapter).get(bgp.getPortId());

        Port portOut = adapter.getByAdRoute(adRouteId);

        Assert.assertEquals(port, portOut);
    }

    @Test
    public void testGetByBgpSuccess() throws Exception {
        UUID bgpId = UUID.randomUUID();
        Bgp bgp = createTestBgp(UUID.randomUUID(), UUID.randomUUID());
        Port port = createTestMaterializedPort(UUID.randomUUID(),
                UUID.randomUUID(), null);
        doReturn(bgp).when(bgpDaoMock).get(bgpId);
        doReturn(port).when(adapter).get(bgp.getPortId());

        Port portOut = adapter.getByBgp(bgpId);

        Assert.assertEquals(port, portOut);
    }

    @Test
    public void testGetByVpnSuccess() throws Exception {
        UUID vpnId = UUID.randomUUID();
        Vpn vpn = createTestVpn(UUID.randomUUID(), UUID.randomUUID());
        Port port = createTestMaterializedPort(UUID.randomUUID(),
                UUID.randomUUID(), null);
        doReturn(vpn).when(vpnDaoMock).get(vpnId);
        doReturn(port).when(adapter).get(vpn.getPublicPortId());

        Port portOut = adapter.getByVpn(vpnId);

        Assert.assertEquals(port, portOut);
    }

    @Test
    public void testListBridgePortsSuccess() throws Exception {
        UUID bridgeId = UUID.randomUUID();
        Set<UUID> ids = createTestIds(3);
        Port port = createTestBridgePort(UUID.randomUUID(), bridgeId);

        doReturn(ids).when(daoMock).getBridgePortIds(bridgeId);
        doReturn(port).when(adapter).get(any(UUID.class));

        List<Port> ports = adapter.listBridgePorts(bridgeId);

        Assert.assertEquals(3, ports.size());
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(BridgePort.class, ports.get(i).getClass());
        }
    }

    @Test
    public void testListRouterPortsSuccess() throws Exception {
        UUID routerId = UUID.randomUUID();
        Set<UUID> ids = createTestIds(3);
        Port port = createTestMaterializedPort(UUID.randomUUID(), routerId,
                null);

        doReturn(ids).when(daoMock).getRouterPortIds(routerId);
        doReturn(port).when(adapter).get(any(UUID.class));

        List<Port> ports = adapter.listRouterPorts(routerId);

        Assert.assertEquals(3, ports.size());
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(MaterializedRouterPort.class, ports.get(i)
                    .getClass());
        }
    }
}
