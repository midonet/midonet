/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.DhcpHost;
import com.midokura.midolman.mgmt.data.dto.DhcpSubnet;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midolman.state.StateAccessException;

public interface DhcpDao {

    /**
     * Create DHCP configuration for an L3 subnet of an L2 network.
     *
     * @param bridgeId
     * @param subnet
     * @throws StateAccessException
     */
    void createSubnet(UUID bridgeId, DhcpSubnet subnet)
            throws StateAccessException;

    /**
     * Update DHCP configuration for an L3 subnet of an L2 network.
     *
     * @param bridgeId
     * @param subnet
     * @throws StateAccessException
     */
    void updateSubnet(UUID bridgeId, DhcpSubnet subnet)
            throws StateAccessException;

    /**
     * Delete the DHCP configuration for an L3 subnet of an L2 network.
     *
     * @param bridgeId
     * @param subnetAddr
     * @throws StateAccessException
     */
    void deleteSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException;

    /**
     * Get the DHCP configuration for an L3 subnet of an L2 network.
     *
     * @param bridgeId
     * @param subnetAddr
     * @returns
     * @throws StateAccessException
     */
    DhcpSubnet getSubnet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException;

    /**
     * List all the L3 subnets of an L2 network that have DHCP configuration.
     *
     * @param bridgeId
     * @return
     * @throws StateAccessException
     */
    List<IntIPv4> listSubnets(UUID bridgeId) throws StateAccessException;

    /**
     * Get all the L3 subnet DHCP configurations.
     *
     * @param bridgeId
     * @return
     * @throws StateAccessException
     */
    List<DhcpSubnet> getSubnets(UUID bridgeId) throws StateAccessException;

    /**
     * Create a DHCP host assignment in an L3 subnet for a MAC address on an L2.
     *
     * @param bridgeId
     * @param subnet
     * @param host
     * @throws StateAccessException
     */
    void createHost(UUID bridgeId, IntIPv4 subnet, DhcpHost host)
            throws StateAccessException;

    /**
     * Update the DHCP host assignment for a specific MAC address.
     *
     * @param bridgeId
     * @param subnet
     * @param host
     * @throws StateAccessException
     */
    void updateHost(UUID bridgeId, IntIPv4 subnet, DhcpHost host)
            throws StateAccessException;

    /**
     * Get the DHCP host assignment for a MAC address on an L2 network.
     *
     * @param bridgeId
     * @param subnet
     * @param mac
     * @return
     * @throws StateAccessException
     */
    DhcpHost getHost(UUID bridgeId, IntIPv4 subnet, String mac)
            throws StateAccessException;

    /**
     * Delete a DHCP host assignment.
     *
     * @param bridgId
     * @param subnet
     * @param mac
     * @throws StateAccessException
     */
    void deleteHost(UUID bridgId, IntIPv4 subnet, String mac)
            throws StateAccessException;

    /**
     * List the MAC addresses of all hosts in a subnet that have DHCP
     * assignments.
     *
     * @param bridgeId
     * @param subnet
     * @return
     * @throws StateAccessException
     */
    List<MAC> listHosts(UUID bridgeId, IntIPv4 subnet)
            throws StateAccessException;

    /**
     * Get all the DHCP host assignments within an L3 subnet of an L2 network.
     *
     * @param bridgeId
     * @param subnet
     * @return
     * @throws StateAccessException
     */
    List<DhcpHost> getHosts(UUID bridgeId, IntIPv4 subnet)
            throws StateAccessException;

}
