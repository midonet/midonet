/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.DhcpDao;
import com.midokura.midolman.mgmt.data.dto.DhcpHost;
import com.midokura.midolman.mgmt.data.dto.DhcpSubnet;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager.Host;
import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager.Subnet;
import com.midokura.midolman.state.StateAccessException;

public class DhcpDaoImpl implements DhcpDao {
    private final static Logger log = LoggerFactory
            .getLogger(DhcpDaoImpl.class);

    private final BridgeDhcpZkManager dhcpMgr;

    public DhcpDaoImpl(BridgeDhcpZkManager dhcpMgr) {
        this.dhcpMgr = dhcpMgr;
    }

    @Override
    public void createSubnet(UUID bridgeId, DhcpSubnet subnet)
            throws StateAccessException {
        dhcpMgr.createSubnet(bridgeId, subnet.toSubnet());
    }

    @Override
    public void updateSubnet(UUID bridgeId, DhcpSubnet subnet)
            throws StateAccessException {
        dhcpMgr.updateSubnet(bridgeId, subnet.toSubnet());
    }

    @Override
    public void deleteSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        dhcpMgr.deleteSubnet(bridgeId, subnetAddr);
    }

    @Override
    public DhcpSubnet getSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        return DhcpSubnet.fromSubnet(dhcpMgr.getSubnet(bridgeId, subnetAddr));
    }

    @Override
    public List<IntIPv4> listSubnets(UUID bridgeId) throws StateAccessException {
        return dhcpMgr.listSubnets(bridgeId);
    }

    @Override
    public List<DhcpSubnet> getSubnets(UUID bridgeId)
            throws StateAccessException {
        List<Subnet> subnets = dhcpMgr.getSubnets(bridgeId);
        List<DhcpSubnet> result = new ArrayList<DhcpSubnet>();
        for (Subnet subnet : subnets)
            result.add(DhcpSubnet.fromSubnet(subnet));
        return result;
    }

    @Override
    public void createHost(UUID bridgeId, IntIPv4 subnet, DhcpHost host)
            throws StateAccessException {
        dhcpMgr.addHost(bridgeId, subnet, host.toStateHost());
    }

    @Override
    public void updateHost(UUID bridgeId, IntIPv4 subnet, DhcpHost host)
            throws StateAccessException {
        dhcpMgr.updateHost(bridgeId, subnet, host.toStateHost());
    }

    @Override
    public DhcpHost getHost(UUID bridgeId, IntIPv4 subnet, String mac)
            throws StateAccessException {
        return DhcpHost.fromStateHost(dhcpMgr.getHost(bridgeId, subnet, mac));
    }

    @Override
    public void deleteHost(UUID bridgId, IntIPv4 subnet, String mac)
            throws StateAccessException {
        dhcpMgr.deleteHost(bridgId, subnet, mac);
    }

    @Override
    public List<MAC> listHosts(UUID bridgeId, IntIPv4 subnet)
            throws StateAccessException {
        return dhcpMgr.listHosts(bridgeId, subnet);
    }

    @Override
    public List<DhcpHost> getHosts(UUID bridgeId, IntIPv4 subnet)
            throws StateAccessException {
        log.debug("DhcpDaoImpl.getHosts: entered with bridge: " + bridgeId);
        List<Host> hosts = dhcpMgr.getHosts(bridgeId, subnet);
        List<DhcpHost> result = new ArrayList<DhcpHost>();
        for (Host h : hosts)
            result.add(DhcpHost.fromStateHost(h));
        return result;
    }
}
