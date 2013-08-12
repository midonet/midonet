/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.api;

import com.google.common.base.Predicate;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.client.MidonetApi;
import org.midonet.client.RouterPredicates;
import org.midonet.client.exception.HttpNotFoundException;
import org.midonet.client.resource.*;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;
import org.midonet.client.resource.AdRoute;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.Port;
import org.midonet.client.resource.PortGroup;
import org.midonet.client.resource.Route;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.ws.rs.core.MultivaluedMap;
import java.net.URI;
import java.util.UUID;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.or;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID;


/*
 * NOTE:
 * This test is temporarily moved from midonet-client since this depends mgmt
 * and having this in midonet-client causes circular dependency.
 */
public class ClientTest extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(ClientTest.class);

    private MidonetApi api;

    public ClientTest() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() {
        URI baseUri = resource().getURI();
        System.out.println("URI: " + resource().getURI());
        api = new MidonetApi(baseUri.toString());
        //api = new MidonetApi("http://localhost:8080/midonet-api/");
        api.enableLogging();
    }

    @Test
    public void test() {
        // Test for chains
        RuleChain c1 = api.addChain().tenantId("tenant-1").name("chain-1")
                           .create();
        RuleChain c2 = api.addChain().tenantId("tenant-1").name("chain-2")
                           .create();

        // Test GET with ID
        assertThat(c1.getId(), is(notNullValue()));
        assertThat(c2.getId(), is(notNullValue()));
        c1 = api.getChain(c1.getId());
        assertThat(c1, is(notNullValue()));
        c2 = api.getChain(c2.getId());
        assertThat(c2, is(notNullValue()));

        MultivaluedMap<String, String> qTenant1 = new MultivaluedMapImpl();
        qTenant1.add("tenant_id", "tenant-1");

        assertThat(api.getChains(qTenant1).size(), is(2));
//        assertThat(api.getChains().findBy("name", "chain-1").getId(),
//                is(c1.getId()));

        // Test for port groups
        PortGroup pg1 = api.addPortGroup().tenantId("tenant-1").name("pg-1")
                            .create();
        PortGroup pg2 = api.addPortGroup().tenantId("tenant-1").name("pg-2")
                            .create();

        // Test GET with ID
        assertThat(pg1.getId(), is(notNullValue()));
        assertThat(pg2.getId(), is(notNullValue()));
        pg1 = api.getPortGroup(pg1.getId());
        assertThat(pg1, is(notNullValue()));
        pg2 = api.getPortGroup(pg2.getId());
        assertThat(pg2, is(notNullValue()));


        ResourceCollection<PortGroup> pgs = api
            .getPortGroups(qTenant1);
        assertThat(pgs.size(), is(2));
        assertThat(pgs.findBy("name", "pg-1").getId(),
                   is(pg1.getId()));


        pg1.delete();
        assertThat(api.getPortGroups(qTenant1).size(), is(1));


        // Test for bridge
        Bridge b1 = api.addBridge().tenantId("tenant-1").name("bridge-1")
                        .create();
        Bridge b2 = api.addBridge().tenantId("tenant-1").name("bridge-2")
                        .create();

        // Test GET with ID
        assertThat(b1.getId(), is(notNullValue()));
        assertThat(b2.getId(), is(notNullValue()));
        b1 = api.getBridge(b1.getId());
        assertThat(b1, is(notNullValue()));
        b2 = api.getBridge(b2.getId());
        assertThat(b2, is(notNullValue()));

        b2 = b2.name("bridge-222").update();

        assertThat(api.getBridges(qTenant1).size(), is(2));
        for (Bridge b : api.getBridges(qTenant1)) {
            log.debug("BRIDGE: {}", b);
        }
        b2.delete();
        assertThat(api.getBridges(qTenant1).size(), is(1));

        // Recreate, we'll use it later to link via VLAN-tagged interior port
        // to B1
        b2 = api.addBridge().tenantId("tenant-1").name("bridge-2").create();
        assertThat(api.getBridges(qTenant1).size(), is(2));

        // Bridge port
        short vlanId = 234;
        BridgePort bp1 = (BridgePort) b1.addExteriorPort().create();
        BridgePort bp2 = (BridgePort) b1.addInteriorPort().create();
        BridgePort bp3 = (BridgePort) b1.addInteriorPort().create();
        BridgePort bp4 = (BridgePort) b1.addInteriorPort().create();
        BridgePort bpVlan = (BridgePort) b1.addInteriorPort()
                                           .vlanId(vlanId).create();
        BridgePort b2pVlan = (BridgePort)b2.addInteriorPort()
                                           .vlanId(vlanId).create();

        log.debug("bp1: {}", bp1);
        log.debug("bp2: {}", bp2);
        log.debug("bp3: {}", bp3);
        log.debug("bp4: {}", bp4);
        log.debug("b2pVlan: {}", b2pVlan);

        // Test GET with ID
        assertThat(bp1.getId(), is(notNullValue()));
        assertThat(bp2.getId(), is(notNullValue()));
        assertThat(bp3.getId(), is(notNullValue()));
        assertThat(bp4.getId(), is(notNullValue()));
        assertThat(b2pVlan.getId(), is(notNullValue()));
        Port p = api.getPort(bp1.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(BridgePort.class)));
        p = api.getPort(bp2.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(BridgePort.class)));
        p = api.getPort(bp3.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(BridgePort.class)));
        p = api.getPort(bp4.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(BridgePort.class)));
        p = api.getPort(b2pVlan.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(BridgePort.class)));

        assertThat(b1.getPorts().size(), is(5));
        assertThat(b2.getPorts().size(), is(1));

        bp1.vifId(UUID.randomUUID()).update();
        bp2.outboundFilterId(UUID.randomUUID()).update();
        log.debug("bp1: {}", bp1);
        assertThat(bp1.getVifId(), notNullValue());
        log.debug("bp2: {}", bp2);
        assertThat(bp2.getOutboundFilterId(), notNullValue());

        // MAC-port mappings.
        final String mac1 = "01:23:45:67:89:ab";
        final String mac2 = "12:34:56:78:9a:bc";
        MacPort[] mp = new MacPort[4];
        mp[0] = b1.addMacPort(null, mac1, bp2.getId()).create();
        mp[1] = b1.addMacPort(null, mac2, bp3.getId()).create();
        mp[2] = b1.addMacPort(bpVlan.getVlanId(), mac1, bpVlan.getId())
                .create();
        mp[3] = b1.addMacPort(bpVlan.getVlanId(), mac2, bpVlan.getId())
                .create();

        ResourceCollection<MacPort> macTable = b1.getMacTable(null);
        assertThat(macTable.size(), equalTo(mp.length));
        for (int i = 0; i < mp.length; i++)
            assertThat(String.format("Should contain mp[%d]", i),
                    macTable.contains(mp[i]));

        macTable = b1.getMacTable(UNTAGGED_VLAN_ID);
        assertThat("Should contain mp[0]", macTable.contains(mp[0]));
        assertThat("Should contain mp[1]", macTable.contains(mp[1]));

        macTable = b1.getMacTable(bpVlan.getVlanId());
        assertThat("Should contain mp[2]", macTable.contains(mp[2]));
        assertThat("Should contain mp[3]", macTable.contains(mp[3]));

        for(int i = 0; i < mp.length; i++) {
            MacPort result = b1.getMacPort(
                    mp[i].getVlanId(), mp[i].getMacAddr(), mp[i].getPortId());
            assertThat(result, equalTo(mp[i]));
        }

        for (int i = 0; i < mp.length; i++) {
            mp[i].delete();
            try {
                // Should throw HttpNotFoundException, since we just deleted it.
                MacPort result = b1.getMacPort(mp[i].getVlanId(),
                        mp[i].getMacAddr(), mp[i].getPortId());
            } catch (HttpNotFoundException ex) {
                continue;
            }
            throw new RuntimeException(String.format("mp[%d] not deleted."));
        }

        // subnet
        DhcpSubnet sn1 = b1.addDhcpSubnet()
                       .subnetPrefix("192.168.10.0")
                       .subnetLength(24)
                       .create();
        DhcpSubnet sn2 = b1.addDhcpSubnet()
                       .subnetPrefix("192.168.20.0")
                       .subnetLength(24)
                       .create();

        assertThat(b1.getDhcpSubnets().size(), is(2));


        // DhcpSubnet Host
        DhcpHost sh1 = sn1.addDhcpHost()
                            .macAddr("00:00:00:aa:bb:cc")
                            .ipAddr("192.168.10.2")
                            .create();
        DhcpHost sh2 = sn1.addDhcpHost()
                            .macAddr("00:00:00:aa:bb:cd")
                            .ipAddr("192.168.10.3")
                            .create();

        log.debug("sh1: {}", sh1);
        log.debug("sh2: {}", sh2);

        assertThat(sn1.getDhcpHosts().size(), is(2));

        // Try linking b1's interior port to b2's vlan-tagged interior port
        bp4.link(b2pVlan.getId());
        assertThat(bp4.getPeerId(), is(((BridgePort) b2pVlan.get()).getId()));
        assertThat(b2pVlan.getPeerId(), is(((BridgePort) bp4.get()).getId()));
        assertThat(b1.getPeerPorts().size(), is(1));
        assertThat(b2.getPeerPorts().size(), is(1));
        bp4.unlink();
        assertThat(((BridgePort) bp4.get()).getPeerId(), is(nullValue()));
        assertThat(((BridgePort) b2pVlan.get()).getPeerId(), is(nullValue()));
        bp4.delete();
        b2pVlan.delete();
        assertThat(b1.getPorts().size(), is(4));
        assertThat(b2.getPorts().size(), is(0));
        assertThat(b1.getPeerPorts().size(), is(0));
        assertThat(b2.getPeerPorts().size(), is(0));

        // Router
        Router r1 = api.addRouter().tenantId("tenant-1").name("router-1")
                        .create();
        Router r2 = api.addRouter().tenantId("tenant-1").name("router-2")
                        .create();

        // Test GET with ID
        assertThat(r1.getId(), is(notNullValue()));
        assertThat(r2.getId(), is(notNullValue()));
        r1 = api.getRouter(r1.getId());
        assertThat(r1, is(notNullValue()));
        r2 = api.getRouter(r2.getId());
        assertThat(r2, is(notNullValue()));

        assertThat(api.getRouters(qTenant1).size(), is(2));
//        assertThat(api.getRouters().findBy("name", "router-1").
//            getName(), is("router-1"));


        log.debug("find result: {}", api.getRouters(qTenant1).find(
            and(RouterPredicates.byId(r1.getId()),
                RouterPredicates.byName("router-1"))));

        log.debug("find result: {}", api.getRouters(qTenant1).find(
            or(
                new RouterPredicates.Builder().name("router-1").build(),
                new RouterPredicates.Builder().id(r1.getId()).build()

            ))
        );

        api.getRouters(qTenant1).find(
            new RouterPredicates.Builder().name("router-1")
                                          .id(r1.getId()).build());


        r1.name("router-111").update();
        api.getRouters(qTenant1);
//        assertThat(api.routers().<Router>findBy("name", "router-111").getName(),
//                is("router-111"));

        r2.delete();
        assertThat(api.getRouters(qTenant1).size(), is(1));

        RouterPort mrp1 = (RouterPort) r1.addExteriorRouterPort()
                                         .portAddress("1.1.1.1")
                                         .networkAddress("1.1.1.0")
                                         .networkLength(24)
                                         .create();

        RouterPort mrp2 = (RouterPort) r1.addExteriorRouterPort()
                                         .portAddress("1.1.1.2")
                                         .networkAddress("1.1.1.0")
                                         .networkLength(24)
                                         .create();

        RouterPort lrp1 = (RouterPort) r1.addInteriorRouterPort()
                                         .portAddress("2.2.2.1")
                                         .networkAddress("2.2.2.0")
                                         .networkLength(24)
                                         .create();

        RouterPort lrp2 = (RouterPort) r1.addInteriorRouterPort()
                                         .portAddress("2.2.2.2")
                                         .networkAddress("2.2.2.0")
                                         .networkLength(24)
                                         .create();

        RouterPort lrp3 = (RouterPort) r1.addInteriorRouterPort()
                                         .portAddress("2.2.2.3")
                                         .networkAddress("2.2.2.0")
                                         .networkLength(24)
                                         .create();

        // Test GET with ID
        assertThat(mrp1.getId(), is(notNullValue()));
        assertThat(mrp2.getId(), is(notNullValue()));
        assertThat(lrp1.getId(), is(notNullValue()));
        assertThat(lrp2.getId(), is(notNullValue()));
        assertThat(lrp3.getId(), is(notNullValue()));
        p = api.getPort(mrp1.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(RouterPort.class)));
        p = api.getPort(mrp2.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(RouterPort.class)));
        p = api.getPort(lrp1.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(RouterPort.class)));
        p = api.getPort(lrp2.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(RouterPort.class)));
        p = api.getPort(lrp3.getId());
        assertThat(p, is(notNullValue()));
        assertThat(p, is(instanceOf(RouterPort.class)));

        assertThat(r1.getPorts(null).size(), is(5));

        lrp2.link(lrp1.getId());
        lrp3.link(bp3.getId());

        assertThat(lrp2.getPeerId(), is(((RouterPort) lrp1.get()).getId()));

        ResourceCollection<Port> peerPorts = r1.getPeerPorts(null);

        lrp2.unlink();
        lrp3.unlink();

        assertThat(((RouterPort) lrp1.get()).getPeerId(), nullValue());
        RouterPort rp1 = r1.getPorts(null).findBy("portAddress", "1.1.1.2");
        RouterPort rp1_ = r1.getPorts(null).find(new Predicate<RouterPort>() {
            @Override
            public boolean apply(@Nullable RouterPort input) {
                return input.getPortAddress().equals("1.1.1.2");
            }
        });

        log.debug("RouterPort rp1_={}", rp1_);


        bp3.link(lrp2.getId());
        assertThat(((BridgePort) bp3.get()).getPeerId(), is(lrp2.getId()));


        assertThat(b1.getPeerPorts().size(), is(1));
        peerPorts = b1.getPeerPorts();
        peerPorts.get(0).getLocalDto();

        bp3.unlink();
        assertThat(((BridgePort) bp3.get()).getPeerId(), nullValue());

        // bgp

        Bgp bgp1 = mrp1.addBgp().localAS(12345).peerAS(67890)
                       .peerAddr("1.1.1.1").create();

        log.debug("bgp1={}", bgp1);

        // Test GET with ID
        assertThat(bgp1.getId(), is(notNullValue()));
        bgp1 = api.getBgp(bgp1.getId());
        assertThat(bgp1, is(notNullValue()));

        assertThat(mrp1.getBgps(null).size(), is(1));

        AdRoute ar1 = bgp1.addAdRoute()
                          .nwPrefix("14.128.23.0")
                          .prefixLength(27)
                          .create();

        // Test GET with ID
        assertThat(ar1.getId(), is(notNullValue()));
        ar1 = api.getAdRoute(ar1.getId());
        assertThat(ar1, is(notNullValue()));

        assertThat(bgp1.getAdRoutes(null).size(), is(1));


        //Routes - there are 5 routes added automatically. One to each router
        //port's address.
        assertThat(r1.getRoutes(null).size(), is(5));

        Route rte1 = r1.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
          .dstNetworkAddr("10.10.10.0").dstNetworkLength(32)
          .type("Normal").nextHopPort(lrp1.getId()).create();

        Route rte2 = r1.addRoute().srcNetworkAddr("20.20.20.0")
                .srcNetworkLength(24).dstNetworkAddr("10.10.10.0")
                .dstNetworkLength(32).type("Blackhole").nextHopPort(lrp1
                        .getId()).create();

        // Test GET with ID
        assertThat(rte1.getId(), is(notNullValue()));
        assertThat(rte2.getId(), is(notNullValue()));
        rte1 = api.getRoute(rte1.getId());
        assertThat(rte1, is(notNullValue()));
        rte2 = api.getRoute(rte2.getId());
        assertThat(rte2, is(notNullValue()));

        assertThat(r1.getRoutes(null).size(), is(7));

        for (Route r : r1.getRoutes(null)) {
            log.debug("Route {}", r);
        }

        Rule rule1 = c1.addRule().type("accept").create();
        Rule rule2 = c1.addRule().type("reject").nwSrcAddress("20.20.20.0")
              .nwSrcLength(24).create();

        // Test GET with ID
        assertThat(rule1.getId(), is(notNullValue()));
        assertThat(rule2.getId(), is(notNullValue()));
        rule1 = api.getRule(rule1.getId());
        assertThat(rule1, is(notNullValue()));
        rule2 = api.getRule(rule2.getId());
        assertThat(rule2, is(notNullValue()));

        assertThat(c1.getRules().size(), is(2));
        ResourceCollection<Rule> rules = c1.getRules();
        for (Rule r : rules) {
            log.debug("RULE: {}", r.getType());
        }

        rules.findBy("type", "reject");
    }
}
