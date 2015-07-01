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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.data.Rule;
import org.midonet.cluster.rest_api.neutron.models.MetaDataService;
import org.midonet.cluster.rest_api.neutron.models.Subnet;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Subnet;

public final class NeutronZkDataTest extends NeutronPluginTest {

    private DirectoryVerifier dirVerifier;
    private PathBuilder pathBuilder;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        pathBuilder = getPathBuilder();
        dirVerifier = new DirectoryVerifier(getDirectory());
    }

    private void verifyRoute(UUID routerId, String addr, String type,
                             int expectedMatchCnt) {

        String routesPath = pathBuilder.getRoutesPath();

        Map<String, Object> matches = new HashMap<>();
        if (routerId != null) {
            matches.put("routerId", routerId);
        }

        if (type != null) {
            matches.put("nextHop", type);
        }

        matches.put("dstNetworkAddr", addr);
        matches.put("dstNetworkLength", 32);
        matches.put("srcNetworkAddr", "0.0.0.0");
        matches.put("srcNetworkLength", 0);

        dirVerifier.assertChildrenFieldsMatch(routesPath, matches,
                                              expectedMatchCnt);
    }

    private void verifyMetadataRoute(UUID routerId, String srcCidr,
                                     int expectedMatchCnt) {

        String routesPath = pathBuilder.getRoutesPath();
        IPv4Subnet srcSubnet = IPv4Subnet.fromCidr(srcCidr);

        Map<String, Object> matches = new HashMap<>();
        matches.put("srcNetworkAddr", srcSubnet.toNetworkAddress().toString());
        matches.put("srcNetworkLength", srcSubnet.getPrefixLen());
        matches.put("dstNetworkAddr",
                    MetaDataService.IPv4_SUBNET.toNetworkAddress().toString());
        matches.put("dstNetworkLength", 32);
        matches.put("routerId", routerId);

        dirVerifier.assertChildrenFieldsMatch(routesPath, matches,
                                              expectedMatchCnt);
    }

    private void verifyFipDnatRule(int expectedMatchCnt) {

        String rulesPath = pathBuilder.getRulesPath();

        String floatingIpAddr = floatingIp.floatingIpAddress;
        String fixedIpAddr = floatingIp.fixedIpAddress;

        Map<String, Object> matches = new HashMap<>();
        matches.put("type", "ForwardNat");
        matches.put("condition.nwDstIp.address", floatingIpAddr);
        matches.put("natTargets[0].nwStart", fixedIpAddr);
        matches.put("natTargets[0].nwEnd", fixedIpAddr);

        dirVerifier.assertChildrenFieldsMatch(rulesPath, matches,
                                              expectedMatchCnt);
    }

    private void verifyFipSnatRule(int expectedMatchCnt) {

        String rulesPath = pathBuilder.getRulesPath();

        String floatingIpAddr = floatingIp.floatingIpAddress;
        String fixedIpAddr = floatingIp.fixedIpAddress;

        Map<String, Object> matches = new HashMap<>();
        matches.put("type", "ForwardNat");
        matches.put("condition.nwSrcIp.address", fixedIpAddr);
        matches.put("natTargets[0].nwStart", floatingIpAddr);
        matches.put("natTargets[0].nwEnd", floatingIpAddr);

        dirVerifier.assertChildrenFieldsMatch(rulesPath, matches,
                                              expectedMatchCnt);
    }

    private void verifyFloatingIpRules() {

        verifyFipSnatRule(1);
        verifyFipDnatRule(1);

    }

    public void verifyNoFloatingIpRules() {

        verifyFipSnatRule(0);
        verifyFipDnatRule(0);
    }

    @Test
    public void testFloatingIp()
        throws SerializationException, StateAccessException,
               Rule.RuleIndexOutOfBoundsException {

        verifyFloatingIpRules();

        plugin.deleteNetwork(extNetwork.id);

        verifyNoFloatingIpRules();
    }

    @Test
    public void testMetadataRouteDhcpPortDelete()
        throws Rule.RuleIndexOutOfBoundsException, SerializationException,
               StateAccessException {

        // First test the normal case
        verifyMetadataRoute(router.id, subnet.cidr, 1);

        // Delete the DHCP port
        plugin.deletePort(dhcpPort.id);

        // Verify no metadata route
        verifyMetadataRoute(router.id, subnet.cidr, 0);

        // Add a new DHCP port
        plugin.createPort(dhcpPort);

        // Verify that the metadata is re-added
        verifyMetadataRoute(router.id, subnet.cidr, 1);
    }

    @Test
    public void testSubnetUpdateGatewayIp()
        throws SerializationException, StateAccessException {

        String newGatewayIp = "10.0.0.100";

        // Verify the gateway IP and the ports local routes
        verifyRoute(router.id, subnet.gatewayIp, "LOCAL", 1);
        verifyRoute(router.id, newGatewayIp, "LOCAL", 0);

        // Update the subnet's gateway IP
        Subnet subnet2 = new Subnet(subnet.id, subnet.networkId,
                                    subnet.tenantId, subnet.name,
                                    subnet.cidr, subnet.ipVersion,
                                    newGatewayIp, subnet.allocationPools,
                                    subnet.dnsNameservers, subnet.hostRoutes,
                                    subnet.enableDhcp);
        plugin.updateSubnet(subnet.id, subnet2);

        // Verify that the port local routes are updated
        verifyRoute(router.id, subnet.gatewayIp, "LOCAL", 0);
        verifyRoute(router.id, newGatewayIp, "LOCAL", 1);
    }

    @Test
    public void testExternalSubnetUpdateGatewayIp()
        throws SerializationException, StateAccessException {

        String newGatewayIp = "200.200.200.100";

        // Verify the gateway IP and the ports local routes
        verifyRoute(null, extSubnet.gatewayIp, "LOCAL", 1);
        verifyRoute(null, newGatewayIp, "LOCAL", 0);

        // Update the subnet's gateway IP
        Subnet subnet2 = new Subnet(extSubnet.id, extSubnet.networkId,
                                    extSubnet.tenantId, extSubnet.name,
                                    extSubnet.cidr, extSubnet.ipVersion,
                                    newGatewayIp, extSubnet.allocationPools,
                                    extSubnet.dnsNameservers,
                                    extSubnet.hostRoutes, extSubnet.enableDhcp);
        plugin.updateSubnet(extSubnet.id, subnet2);

        // Verify that the port local routes are updated
        verifyRoute(null, extSubnet.gatewayIp, "LOCAL", 0);
        verifyRoute(null, newGatewayIp, "LOCAL", 1);
    }

    @Test
    public void testPoolHeathMonitorMapping() {

        String mappingPath = pathBuilder.getPoolHealthMonitorMappingsPath(
            pool.id, healthMonitor.id);
        dirVerifier.assertPathExists(mappingPath);

        plugin.deleteHealthMonitor(healthMonitor.id);
        dirVerifier.assertPathNotExists(mappingPath);
    }

    private void verifySecurityGroupRule()
        throws SerializationException, StateAccessException {
        Rule mr = dataClient.rulesGet(securityGroupRule.id);
        if (securityGroupRule.isEgress() &&
            securityGroupRule.remoteGroupId != null) {
            Assert.assertTrue(mr.getCondition()
                                .ipAddrGroupIdDst
                                .equals(securityGroupRule.remoteGroupId));
        }

        if (securityGroupRule.isIngress() &&
            securityGroupRule.remoteGroupId != null) {
            Assert.assertTrue(mr.getCondition()
                                .ipAddrGroupIdSrc
                                .equals(securityGroupRule.remoteGroupId));
        }

        if (securityGroupRule.ethertype != null) {
            Assert.assertTrue(mr.getCondition().etherType.equals(
                    securityGroupRule.ethertype()));
        }

        if (securityGroupRule.protocol != null) {
            Assert.assertTrue(securityGroupRule.protocol.number() ==
                              mr.getCondition().nwProto);
        }

        if (securityGroupRule.portRangeMax != null &&
            securityGroupRule.isIngress()) {
            Assert.assertTrue(
                securityGroupRule.portRangeMax.equals(
                        mr.getCondition().tpSrc.end()));
        }
    }

    @Test
    public void testSecurityGroupCreateRemoteGroup()
        throws SerializationException, StateAccessException {
        securityGroupRule.remoteGroupId = securityGroup.id;
        plugin.createSecurityGroupRule(securityGroupRule);
        verifySecurityGroupRule();
    }

    @Test
    public void testSecurityGroupCreateNullEthertype()
        throws SerializationException, StateAccessException {
        securityGroupRule.ethertype = null;
        plugin.createSecurityGroupRule(securityGroupRule);
        verifySecurityGroupRule();
    }

    @Test
    public void testSecurityGroupCreateNullProtocol()
        throws SerializationException, StateAccessException {
        securityGroupRule.protocol = null;
        plugin.createSecurityGroupRule(securityGroupRule);
        verifySecurityGroupRule();
    }

    @Test
    public void testSecurityGroupCreateNullMin()
        throws SerializationException, StateAccessException {
        securityGroupRule.portRangeMin = null;
        securityGroupRule.portRangeMax = 100;
        plugin.createSecurityGroupRule(securityGroupRule);
        verifySecurityGroupRule();
    }

    @Test
    public void testSecurityGroupCreateNullMax()
        throws SerializationException, StateAccessException {
        securityGroupRule.portRangeMax = null;
        securityGroupRule.portRangeMin = 100;
        plugin.createSecurityGroupRule(securityGroupRule);
        verifySecurityGroupRule();
    }

    @Test
    public void testSecurityGroupCreateNullDirection()
        throws SerializationException, StateAccessException {
        securityGroupRule.direction = null;
        plugin.createSecurityGroupRule(securityGroupRule);
        verifySecurityGroupRule();
    }
}
