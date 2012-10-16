/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midolman.mgmt;

import com.google.common.base.Predicate;

import com.midokura.midolman.mgmt.rest_api.FuncTest;
import com.midokura.midonet.client.RouterPredicates;
import com.midokura.midonet.client.exception.HttpBadRequestException;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.resource.*;

import com.sun.jersey.core.util.MultivaluedMapImpl;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;
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


/*
 * NOTE:
 * This test is temporarily moved from midonet-client since this depends mgmt
 * and having this in midonet-client causes circular dependency.
 */
public class ClientTest extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(ClientTest.class);

    private MidonetMgmt mgmt;

    public ClientTest() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() {
        URI baseUri = resource().getURI();
        System.out.println("URI: " + resource().getURI());
        mgmt = new MidonetMgmt(baseUri.toString());
        //mgmt = new MidonetMgmt("http://localhost:8080/midolmanj-mgmt/");
        mgmt.enableLogging();
    }

    @Test
    public void test() {
        // Test for chains
        RuleChain c1 = mgmt.addChain().tenantId("tenant-1").name("chain-1")
                           .create();
        RuleChain c2 = mgmt.addChain().tenantId("tenant-1").name("chain-2")
                           .create();

        MultivaluedMap<String, String> qTenant1 = new MultivaluedMapImpl();
        qTenant1.add("tenant_id", "tenant-1");

        assertThat(mgmt.getChains(qTenant1).size(), is(2));
//        assertThat(mgmt.getChains().findBy("name", "chain-1").getId(),
//                is(c1.getId()));

        // Test for port groups
        PortGroup pg1 = mgmt.addPortGroup().tenantId("tenant-1").name("pg-1")
                            .create();
        PortGroup pg2 = mgmt.addPortGroup().tenantId("tenant-1").name("pg-2")
                            .create();

        ResourceCollection<PortGroup> pgs = mgmt
            .getPortGroups(qTenant1);
        assertThat(pgs.size(), is(2));
        assertThat(pgs.findBy("name", "pg-1").getId(),
                   is(pg1.getId()));


        pg1.delete();
        assertThat(mgmt.getPortGroups(qTenant1).size(), is(1));


        // Test for bridge
        Bridge b1 = mgmt.addBridge().tenantId("tenant-1").name("bridge-1")
                        .create();
        Bridge b2 = mgmt.addBridge().tenantId("tenant-1").name("bridge-2")
                        .create();

        b2 = b2.name("bridge-222").update();

        assertThat(mgmt.getBridges(qTenant1).size(), is(2));
        for (Bridge b : mgmt.getBridges(qTenant1)) {
            log.debug("BRIDGE: {}", b);
        }
        b2.delete();
        assertThat(mgmt.getBridges(qTenant1).size(), is(1));


        // Bridge port
        BridgePort bp1 = (BridgePort) b1.addMaterializedPort().create();
        BridgePort bp2 = (BridgePort) b1.addLogicalPort().create();
        BridgePort bp3 = (BridgePort) b1.addLogicalPort().create();

        log.debug("bp1: {}", bp1);
        log.debug("bp2: {}", bp2);
        log.debug("bp3: {}", bp3);

        assertThat(b1.getPorts().size(), is(3));

        bp1.vifId(UUID.randomUUID()).update();
        bp2.outboundFilterId(UUID.randomUUID()).update();
        log.debug("bp1: {}", bp1);
        assertThat(bp1.getVifId(), notNullValue());
        log.debug("bp2: {}", bp2);
        assertThat(bp2.getOutboundFilterId(), notNullValue());

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


        // Router
        Router r1 = mgmt.addRouter().tenantId("tenant-1").name("router-1")
                        .create();
        Router r2 = mgmt.addRouter().tenantId("tenant-1").name("router-2")
                        .create();

        assertThat(mgmt.getRouters(qTenant1).size(), is(2));
//        assertThat(mgmt.getRouters().findBy("name", "router-1").
//            getName(), is("router-1"));


        log.debug("find result: {}", mgmt.getRouters(qTenant1).find(
            and(RouterPredicates.byId(r1.getId()),
                RouterPredicates.byName("router-1"))));

        log.debug("find result: {}", mgmt.getRouters(qTenant1).find(
            or(
                new RouterPredicates.Builder().name("router-1").build(),
                new RouterPredicates.Builder().id(r1.getId()).build()

            ))
        );

        mgmt.getRouters(qTenant1).find(
            new RouterPredicates.Builder().name("router-1")
                                          .id(r1.getId()).build());


        r1.name("router-111").update();
        mgmt.getRouters(qTenant1);
//        assertThat(mgmt.routers().<Router>findBy("name", "router-111").getName(),
//                is("router-111"));

        r2.delete();
        assertThat(mgmt.getRouters(qTenant1).size(), is(1));

        RouterPort mrp1 = (RouterPort) r1.addMaterializedRouterPort()
                                         .portAddress("1.1.1.1")
                                         .networkAddress("1.1.1.0")
                                         .networkLength(24)
                                         .create();

        RouterPort mrp2 = (RouterPort) r1.addMaterializedRouterPort()
                                         .portAddress("1.1.1.2")
                                         .networkAddress("1.1.1.0")
                                         .networkLength(24)
                                         .create();

        RouterPort lrp1 = (RouterPort) r1.addLogicalRouterPort()
                                         .portAddress("2.2.2.1")
                                         .networkAddress("2.2.2.0")
                                         .networkLength(24)
                                         .create();

        RouterPort lrp2 = (RouterPort) r1.addLogicalRouterPort()
                                         .portAddress("2.2.2.2")
                                         .networkAddress("2.2.2.0")
                                         .networkLength(24)
                                         .create();

        RouterPort lrp3 = (RouterPort) r1.addLogicalRouterPort()
                                         .portAddress("2.2.2.3")
                                         .networkAddress("2.2.2.0")
                                         .networkLength(24)
                                         .create();


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

        assertThat(mrp1.getBgps(null).size(), is(1));

        AdRoute ar1 = bgp1.addAdRoute()
                          .nwPrefix("14.128.23.0")
                          .prefixLength(27)
                          .create();

        assertThat(bgp1.getAdRoutes(null).size(), is(1));


        //Routes - there are 5 routes added automatically. One to each router
        //port's address.
        assertThat(r1.getRoutes(null).size(), is(5));

        r1.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
          .dstNetworkAddr("10.10.10.0").dstNetworkLength(32)
          .type("Normal").nextHopPort(lrp1.getId()).create();

        r1.addRoute().srcNetworkAddr("20.20.20.0").srcNetworkLength(24)
          .dstNetworkAddr("10.10.10.0").dstNetworkLength(32)
          .type("Blackhole").nextHopPort(lrp1.getId()).create();


        assertThat(r1.getRoutes(null).size(), is(7));

        for (Route r : r1.getRoutes(null)) {
            log.debug("Route {}", r);
        }


        try {
            c1.addRule().type("accept").create();
            c1.addRule().type("reject").nwSrcAddress("20.20.20.0")
              .nwSrcLength(24).create();

        } catch (HttpBadRequestException e) {
            log.debug("==============================");
        }

        assertThat(c1.getRules().size(), is(2));
        ResourceCollection<Rule> rules = c1.getRules();
        for (Rule r : rules) {
            log.debug("RULE: {}", r.getType());
        }

        rules.findBy("type", "reject");
    }
}