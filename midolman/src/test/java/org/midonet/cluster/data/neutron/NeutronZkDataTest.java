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

import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.data.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.DirectoryVerifier;
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
    public void testMetadataRouteWhenDhcpPortCreatedAfterRouter()
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
}
