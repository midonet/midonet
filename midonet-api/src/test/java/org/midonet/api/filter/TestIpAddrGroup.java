/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.filter;

import java.net.URI;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoIpAddrGroup;
import org.midonet.client.dto.DtoIpAddrGroupAddr;
import org.midonet.client.dto.DtoIpv4AddrGroupAddr;
import org.midonet.client.dto.DtoIpv6AddrGroupAddr;
import org.midonet.client.dto.DtoRule;
import org.midonet.client.dto.DtoRuleChain;
import org.midonet.packets.IPv4Addr$;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.midonet.client.VendorMediaType.APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_IP_ADDR_GROUP_ADDR_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_IP_ADDR_GROUP_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_JSON_V5;
import static org.midonet.client.VendorMediaType.APPLICATION_RULE_JSON_V2;

@RunWith(Enclosed.class)
public class TestIpAddrGroup {

    public static class TestCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private DtoApplication app;

        public TestCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void before() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create one chain for tenant1
            DtoRuleChain chain = new DtoRuleChain();
            chain.setName("Chain1");
            chain.setTenantId("tenant1-id");

            topology = new Topology.Builder(
                    dtoResource, APPLICATION_JSON_V5)
                    .create("chain1", chain).build();

            app = topology.getApplication();
        }

        @Test
        public void testCreateGetListDelete() {

            DtoRuleChain chain = topology.getChain("chain1");

            // Create two IP address groups
            DtoIpAddrGroup group1 = createIPAddrGroup("Group1");
            DtoIpAddrGroup group2 = createIPAddrGroup("Group2");

            // List all ip addr groups
            DtoIpAddrGroup[] groups = dtoResource.getAndVerifyOk(
                    app.getIpAddrGroups(),
                    APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON,
                    DtoIpAddrGroup[].class);
            assertNotNull(groups);
            assertEquals(2, groups.length);

            // Add an IPv4 address
            DtoIpAddrGroupAddr ipv4Addr = addAddrToGroup(group1, "10.0.0.2");
            assertEquals("10.0.0.2", ipv4Addr.getAddr());

            // Add an IPv6 address
            DtoIpAddrGroupAddr ipv6Addr =
                    addAddrToGroup(group1, "2607:f0d0:1002:51:0:0:0:4");
            assertEquals("2607:f0d0:1002:51:0:0:0:4", ipv6Addr.getAddr());

            // Get the list of addresses
            DtoIpAddrGroupAddr[] addrs = getAddrs(group1);
            assertEquals(2, addrs.length);

            // Remove the addresses
            for (DtoIpAddrGroupAddr addr : addrs) {
                dtoResource.deleteAndVerifyNoContent(addr.getUri(),
                        APPLICATION_IP_ADDR_GROUP_ADDR_JSON);
            }

            // Now there should be no address
            addrs = getAddrs(group1);
            assertEquals(0, addrs.length);

            // Now add the rule.
            DtoRule rule = new DtoRule();
            rule.setPosition(1);
            rule.setType(DtoRule.Accept);
            rule.setIpAddrGroupSrc(group1.getId());
            rule.setIpAddrGroupDst(group2.getId());
            rule.setInvIpAddrGroupSrc(true);
            rule.setInvIpAddrGroupDst(true);

            rule = dtoResource.postAndVerifyCreated(
                    chain.getRules(), APPLICATION_RULE_JSON_V2, rule,
                    DtoRule.class);
            assertEquals(chain.getId(), rule.getChainId());
            assertEquals(DtoRule.Accept, rule.getType());
            assertEquals(group1.getId(), rule.getIpAddrGroupSrc());
            assertEquals(group2.getId(), rule.getIpAddrGroupDst());
            assertEquals(true, rule.isInvIpAddrGroupSrc());
            assertEquals(true, rule.isInvIpAddrGroupDst());

            // Delete the groups
            for (DtoIpAddrGroup group : groups) {
                DtoIpAddrGroup g = dtoResource.getAndVerifyOk(group.getUri(),
                        APPLICATION_IP_ADDR_GROUP_JSON, DtoIpAddrGroup.class);
                assertNotNull(g);
                assertEquals(group, g);

                dtoResource.deleteAndVerifyNoContent(g.getUri(),
                        APPLICATION_IP_ADDR_GROUP_JSON);
            }

            groups = dtoResource.getAndVerifyOk(
                    app.getIpAddrGroups(),
                    APPLICATION_IP_ADDR_GROUP_COLLECTION_JSON,
                    DtoIpAddrGroup[].class);
            assertNotNull(groups);
            assertEquals(0, groups.length);

        }

        @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
        @Test
        public void testInvalidIpAddr() {
            String ipString = "10.0.2";
            DtoIpAddrGroup group = createIPAddrGroup("group1");
            DtoIpAddrGroupAddr ipAddr =
                    new DtoIpv4AddrGroupAddr(group.getId(), ipString);
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    group.getAddrs(), APPLICATION_IP_ADDR_GROUP_ADDR_JSON, ipAddr);

            String expectedErrorMessage =
                IPv4Addr$.MODULE$.illegalIPv4String(ipString).getMessage();
            assertEquals(expectedErrorMessage, error.getMessage());
        }

        @Test
        public void testCanonicalization() throws Exception {
            DtoIpAddrGroup group = createIPAddrGroup("group1");

            // Addresses should be canonicalized when retrieving after creation.
            DtoIpAddrGroupAddr ipv4Addr =
                    addAddrToGroup(group, "010.000.000.002");
            assertEquals("10.0.0.2", ipv4Addr.getAddr());

            DtoIpAddrGroupAddr ipv6Addr =
                    addAddrToGroup(group, "1:2:3:4:0005:0006:0007:0008");
            assertEquals("1:2:3:4:5:6:7:8", ipv6Addr.getAddr());

            // Listing addresses should return canonicalized addresses.
            DtoIpAddrGroupAddr[] addrs = getAddrs(group);
            if (addrs[0].getAddr().contains(":")) {
                assertEquals("1:2:3:4:5:6:7:8", addrs[0].getAddr());
                assertEquals("10.0.0.2", addrs[1].getAddr());
            } else {
                assertEquals("10.0.0.2", addrs[0].getAddr());
                assertEquals("1:2:3:4:5:6:7:8", addrs[1].getAddr());
            }

            // Should be able to delete with address in any valid form.
            String uriStr = ipv4Addr.getUri().toString();
            dtoResource.deleteAndVerifyNoContent(
                    new URI(uriStr.replace("10.0.0.2", "10.0.000.002")),
                    APPLICATION_IP_ADDR_GROUP_ADDR_JSON);
            addrs = getAddrs(group);
            assertEquals(1, addrs.length);

            uriStr = ipv6Addr.getUri().toString();
            dtoResource.deleteAndVerifyNoContent(
                    new URI(uriStr.replace("1:2:3:4:5:6:7:8",
                                           "1:02:3:04:5:06:7:08")),
                    APPLICATION_IP_ADDR_GROUP_ADDR_JSON);
            addrs = getAddrs(group);
            assertEquals(0, addrs.length);
        }

        private DtoIpAddrGroup createIPAddrGroup(String name) {
            DtoIpAddrGroup group = new DtoIpAddrGroup();
            group.setName(name);
            DtoIpAddrGroup result = dtoResource.postAndVerifyCreated(
                    app.getIpAddrGroups(), APPLICATION_IP_ADDR_GROUP_JSON,
                    group, DtoIpAddrGroup.class);
            assertEquals(name, result.getName());
            return result;
        }

        private DtoIpAddrGroupAddr addAddrToGroup(DtoIpAddrGroup group, String addr) {
            DtoIpAddrGroupAddr ipAddr = addr.contains(":") ?
                    new DtoIpv6AddrGroupAddr(group.getId(), addr) :
                    new DtoIpv4AddrGroupAddr(group.getId(), addr);
            ipAddr = dtoResource.postAndVerifyCreated(group.getAddrs(),
                    APPLICATION_IP_ADDR_GROUP_ADDR_JSON, ipAddr,
                    DtoIpAddrGroupAddr.class);
            assertNotNull(ipAddr);
            assertEquals(group.getId(), ipAddr.getIpAddrGroupId());
            return ipAddr;
        }

        private DtoIpAddrGroupAddr[] getAddrs(DtoIpAddrGroup group) {
            DtoIpAddrGroupAddr[] addrs = dtoResource.getAndVerifyOk(
                    group.getAddrs(),
                    APPLICATION_IP_ADDR_GROUP_ADDR_COLLECTION_JSON,
                    DtoIpAddrGroupAddr[].class);
            assertNotNull(addrs);
            return addrs;
        }
    }
}
