/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.neutron;

import java.util.List;
import java.util.UUID;

import com.google.common.base.Objects;
import com.google.inject.Inject;

import org.apache.zookeeper.Op;

import org.midonet.cluster.data.Rule;
import org.midonet.cluster.rest_api.neutron.models.IPAllocation;
import org.midonet.cluster.rest_api.neutron.models.Network;
import org.midonet.cluster.rest_api.neutron.models.Port;
import org.midonet.cluster.rest_api.neutron.models.Subnet;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.packets.IPv4Subnet;

public class ExternalNetZkManager extends BaseZkManager {

    private final NetworkZkManager networkZkManager;
    private final L3ZkManager l3ZkManager;
    private final ProviderRouterZkManager providerRouterZkManager;
    private final PortZkManager portZkManager;
    private final RouteZkManager routeZkManager;

    @Inject
    public ExternalNetZkManager(ZkManager zk, PathBuilder paths,
                                Serializer serializer,
                                NetworkZkManager networkZkManager,
                                L3ZkManager l3ZkManager,
                                ProviderRouterZkManager providerRouterZkManager,
                                PortZkManager portZkManager,
                                RouteZkManager routeZkManager) {
        super(zk, paths, serializer);
        this.networkZkManager = networkZkManager;
        this.l3ZkManager = l3ZkManager;
        this.providerRouterZkManager = providerRouterZkManager;
        this.portZkManager = portZkManager;
        this.routeZkManager = routeZkManager;
    }

    public void prepareLinkToProvider(List<Op> ops, Subnet sub)
            throws SerializationException, StateAccessException {

        UUID prId = providerRouterZkManager.getId();

        // Create a port on the bridge
        BridgePortConfig bpCfg = new BridgePortConfig(sub.networkId, true);
        bpCfg.id = UUID.randomUUID();

        // Create a port on the provider router
        RouterPortConfig rpCfg = new RouterPortConfig(prId,
                sub.cidrAddressInt(), sub.cidrAddressLen(), sub.gwIpInt(),
                true);
        rpCfg.id = UUID.randomUUID();

        portZkManager.prepareCreateAndLink(ops, bpCfg, rpCfg);

        // Add a route for the subnet in the gateway router
        routeZkManager.preparePersistPortRouteCreate(ops, UUID.randomUUID(),
                                                     new IPv4Subnet(0, 0),
                                                     sub.ipv4Subnet(), rpCfg.id,
                                                     null, prId,
                                                     rpCfg);
    }

    public void prepareUnlinkFromProvider(List<Op> ops, Subnet sub)
            throws SerializationException, StateAccessException {

        // Remove the linked interior ports on the bridge and the router that
        // is assigned this subnet's CIDR.
        UUID prId = providerRouterZkManager.getId();
        portZkManager.prepareDeleteRouterPorts(ops, prId, sub.ipv4Subnet(),
                true);
    }

    /**
     * When a network is deleted, the corresponding bridge is deleted, which
     * removes all of its ports.  However, it does not remove their peer ports
     * that belong to the provider router.  When external network is removed,
     * call this method to clean up the dangling peer ports.  In addition,
     * all the floating IPs allocated on this network should be deleted.
     */
    public void prepareDeleteExternalNetwork(List<Op> ops, Network net)
            throws SerializationException, StateAccessException,
            Rule.RuleIndexOutOfBoundsException {

        // Delete floating IPs belonging to the ports on this network
        List<Port> ports = networkZkManager.getPorts(net.id);
        for (Port port : ports) {
            if (port.isFloatingIp()) {
                l3ZkManager.prepareDeleteFloatingIp(ops, port.deviceIdUuid());
            }
        }

        UUID prId = providerRouterZkManager.getId();
        List<IPv4Subnet> ipv4Subs = networkZkManager.getIPv4Subnets(net.id);

        // Remove the ports with these subnets but no need to remove the
        // peers since we only care about dangling ports.
        portZkManager.prepareDeleteRouterPorts(ops, prId, ipv4Subs, false);
    }

    /**
     * Handle network update in which the external network flag could have
     * been modified.
     */
    public void prepareUpdateExternalNetwork(List<Op> ops, Network net)
            throws SerializationException, StateAccessException {

        List<Subnet> subs = networkZkManager.getSubnets(net.id);

        // Delete and re-add.  Optimize later
        for (Subnet sub : subs) {
            prepareUnlinkFromProvider(ops, sub);

            if (net.external) {
                prepareLinkToProvider(ops, sub);
            }
        }
    }

    /**
     * Add a route to a specific port on the external network.
     */
    public void prepareCreateExtNetRoute(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        UUID prId = providerRouterZkManager.getId();
        for (IPAllocation ip : port.fixedIps) {

            Subnet sub = networkZkManager.getSubnet(ip.subnetId);
            if (!sub.isIpv4()) {
                continue;
            }

            RouterPortConfig rpCfg = portZkManager.getRouterPort(prId,
                    port.networkId, sub.ipv4Subnet());
            if (rpCfg == null) {
                throw new IllegalStateException(
                        "Router port not found for subnet " + sub.cidr);
            }

            routeZkManager.preparePersistPortRouteCreate(ops, UUID.randomUUID(),
                                                         new IPv4Subnet(0, 0),
                                                         ip.ipv4Subnet(),
                                                         rpCfg.id, null, prId,
                                                         rpCfg);
        }
    }

    /**
     * Delete routes from the provider router when a port on the external
     * network is deleted.
     */
    public void prepareDeleteExtNetRoute(List<Op> ops, Port port)
            throws StateAccessException, SerializationException {

        UUID prId = providerRouterZkManager.getId();
        for (IPAllocation ipAlloc : port.fixedIps) {
            //TODO: check for IP version
            routeZkManager.prepareRoutesDelete(ops, prId, ipAlloc.ipv4Subnet());
        }
    }

    public void prepareUpdateExtSubnet(List<Op> ops, Subnet subnet)
        throws StateAccessException, SerializationException {

        // Update only if the gateway is changed.
        Subnet oldSubnet = networkZkManager.getSubnet(subnet.id);
        if (Objects.equal(oldSubnet.gatewayIp, subnet.gatewayIp)) {
            return;
        }

        RouterPortConfig pCfg = portZkManager.findGatewayRouterPortFromBridge(
            oldSubnet.networkId, oldSubnet.gatewayIpAddr());

        if (pCfg != null) {

            // Subnet is linked to a router, so update the gateway port on
            // this subnet
            portZkManager.prepareUpdatePortAddress(ops, pCfg.id,
                                                   subnet.gwIpInt());
        }
    }

}
