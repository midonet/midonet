/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.URI;

import com.midokura.midonet.smoketest.utils.Tap;
import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import org.apache.velocity.util.introspection.LinkingUberspector;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.openvswitch.BridgeBuilder;
import com.midokura.midolman.openvswitch.BridgeFailMode;
import com.midokura.midolman.openvswitch.ControllerBuilder;
import com.midokura.midolman.openvswitch.ControllerConnectionMode;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.util.Net;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.openflow.ServiceController;

public class SmokeTest {

    private final static Logger log = LoggerFactory.getLogger(SmokeTest.class);


    @Test
    public void test() throws IOException, InterruptedException {

        LibvirtHandler libvirtHandler =  LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        MidolmanMgmt mgmt = new MockMidolmanMgmt();
        // Add the tenant
        Tenant tenant = new Tenant();
        tenant.setId("tenant7");
        URI tenantURI = mgmt.addTenant(tenant);
        log.debug("tenant location: {}", tenantURI);
        // Add a router.
        Router router = new Router();
        String routerName = "router1";
        router.setName(routerName);
        URI routerURI = mgmt.addRouter(tenantURI, router);
        log.debug("router location: {}", routerURI);
        // Get the router.
        router = mgmt.get(routerURI.getPath(), Router.class);
        log.debug("router name: {}", router.getName());
        assertEquals(router.getName(), routerName);

        // Add a materialized router port.
        MaterializedRouterPort port = new MaterializedRouterPort();
        String portAddress = "180.214.47.65";
        port.setNetworkAddress("180.214.47.64");
        port.setNetworkLength(29);
        port.setPortAddress(portAddress);
        port.setLocalNetworkAddress("180.214.47.66");
        port.setLocalNetworkLength(32);
        log.debug("port JSON {}", port.toString());
        URI portURI = mgmt.addRouterPort(routerURI, port);
        log.debug("1st port location: {}", portURI);
        // Get the port.
        port = mgmt.get(portURI.getPath(), MaterializedRouterPort.class);
        log.info("1st port id {}", port.getId());
        log.debug("1st port address: {}", port.getPortAddress());
        assertEquals(port.getPortAddress(), portAddress);
        // Add a route to the port.
        Route rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        String nwDst = "180.214.47.66";
        rt.setDstNetworkAddr(nwDst);
        rt.setDstNetworkLength(32);
        rt.setType(Route.Normal);
        rt.setNextHopPort(port.getId());
        rt.setWeight(10);
        URI routeURI = mgmt.addRoute(routerURI, rt);
        log.debug("1st route location: {}", routeURI);
        // Get the route.
        rt = mgmt.get(routeURI.getPath(), Route.class);
        log.debug("route destination: {}", rt.getDstNetworkAddr());
        assertEquals(rt.getDstNetworkAddr(), nwDst);
        
        OpenvSwitchDatabaseConnection ovsdb;
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);

        String brName = "smoke-br";
        BridgeBuilder brBuilder = ovsdb.addBridge(brName);
        brBuilder.externalId("midolman-vnet",
                "01234567-0123-0123-aaaa-0123456789ab");
        brBuilder.failMode(BridgeFailMode.SECURE);
        brBuilder.otherConfig("hwaddr", "02:aa:bb:11:22:33");
        brBuilder.build();
        // Add the Midolman controller.
        ControllerBuilder ctlBuilder = ovsdb.addBridgeOpenflowController(
                brName, "tcp:127.0.0.1:6633");
        ctlBuilder.connectionMode(ControllerConnectionMode.OUT_OF_BAND);
        ctlBuilder.build();
        // Add a service controller. This test will launch the service
        // controller in order to query the switch statistics.
        ctlBuilder = ovsdb.addBridgeOpenflowController(
                brName, "ptcp:6634");
        ctlBuilder.build();

        // Replace this with a call to Rossella's tap module.
        // Process tapAdd = Runtime.getRuntime().exec(
        // "sudo ip tuntap add dev port1 mode tap");
        // tapAdd.waitFor();
        String portName = "port1";
        PortBuilder pBuilder = ovsdb.addInternalPort(brName, portName);
        pBuilder.externalId("midolman-vnet", port.getId().toString());
        pBuilder.build();
        Thread.sleep(2000);
        Process p = Runtime.getRuntime().exec(
                    "sudo -n ip link set dev port1 arp on mtu 1300 multicast off up");
        p.waitFor();
        log.info("ip link set arp,mtu,multicast - returned {}", p.exitValue());
        p = Runtime.getRuntime().exec(
                    "sudo -n ip addr add 180.214.47.66/24 dev port1");
        p.waitFor();
        log.info("ip addr add - returned {}", p.exitValue());

        // Create another virtual port and its tap and OVS port for 'port2'
        port = new MaterializedRouterPort();
        port.setNetworkAddress("180.214.47.64");
        port.setNetworkLength(29);
        port.setPortAddress(portAddress);
        port.setLocalNetworkAddress("180.214.47.67");
        port.setLocalNetworkLength(32);
        log.debug("port JSON {}", port.toString());
        portURI = mgmt.addRouterPort(routerURI, port);
        log.debug("2nd port location: {}", portURI);
        // Get the port.
        port = mgmt.get(portURI.getPath(), MaterializedRouterPort.class);
        log.debug("2nd port address: {}", port.getPortAddress());
        log.info("2nd port id {}", port.getId());
        assertEquals(port.getPortAddress(), portAddress);
        // Add a route to the port.
        rt = new Route();
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        nwDst = "180.214.47.67";
        rt.setDstNetworkAddr(nwDst);
        rt.setDstNetworkLength(32);
        rt.setType(Route.Normal);
        rt.setNextHopPort(port.getId());
        rt.setWeight(10);
        routeURI = mgmt.addRoute(routerURI, rt);
        log.debug("2nd route location: {}", routeURI);
        // Get the route.
        rt = mgmt.get(routeURI.getPath(), Route.class);
        log.debug("2nd route destination: {}", rt.getDstNetworkAddr());
        assertEquals(rt.getDstNetworkAddr(), nwDst);

        p = Runtime.getRuntime().exec("sudo -n ip tuntap add dev port2 mode tap");
        p.waitFor();
        log.debug("\"sudo -n ip tuntap add dev port2 mode tap\" exited with: {}", p.exitValue());
        p = Runtime.getRuntime().exec("sudo -n ip link set dev port2 up");
        p.waitFor();
        log.debug("\"sudo -n ip link set dev port2 up\" exited with: {}", p.exitValue());
        pBuilder = ovsdb.addSystemPort(brName, "port2");
        pBuilder.externalId("midolman-vnet", port.getId().toString());
        pBuilder.build();
        
        Thread.sleep(20000);

        ICMP icmpReq = new ICMP();
        short id = -12345;
        short seq = -20202;
        byte[] data = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc,
                (byte) 0xdd, (byte) 0xee, (byte) 0xff };
        icmpReq.setEchoRequest(id, seq, data);
        IPv4 ipReq = new IPv4();
        ipReq.setPayload(icmpReq);
        ipReq.setProtocol(ICMP.PROTOCOL_NUMBER);
        // The ping can come from anywhere if one of the next hops is a
        int senderIP = Net.convertStringAddressToInt("180.214.47.67");
        ipReq.setSourceAddress(senderIP);
        ipReq.setDestinationAddress(Net.convertStringAddressToInt("180.214.47.66"));
        Ethernet ethReq = new Ethernet();
        ethReq.setPayload(ipReq);
        ethReq.setEtherType(IPv4.ETHERTYPE);
        ethReq.setDestinationMACAddress(MAC.fromString(Tap.getHwAddress("port2")));
        MAC senderMac = MAC.fromString("ab:cd:ef:01:23:45");
        ethReq.setSourceMACAddress(senderMac);
        byte[] pktData = ethReq.serialize();

        Tap.writeToTap("port2", pktData, pktData.length);
        
        libvirtHandler.setTemplate("basic_template_x86_64");
        VMController vmController = libvirtHandler.newDomain()
                .setDomainName("test_domain")
                .setHostName("hostname")
                .setNetworkDevice("port2")
                .build();
        vmController.startup();
        // the machine should be booting up

        // Create the service controller we'll use to query the switch stats.
        //ServiceController svcController = new ServiceController(
        //        "127.0.0.1", 6634);

        // Start midolman
        /*
        Process mmController = Runtime.getRuntime()
                .exec("sudo /usr/lib/jvm/java-6-openjdk/jre/bin/java "
                        + "-cp ./conf:/usr/share/midolman/midolmanj.jar "
                        + "-Dmidolman.log.dir=. "
                        + "-Dcom.sun.management.jmxremote "
                        + "-Dcom.sun.management.jmxremote.local.only= "
                        + "com.midokura.midolman.Midolman "
                        + "-c ./conf/midolman.conf");
        Thread.sleep(20000);
        Runtime.getRuntime().exec(
                String.format(
                    "ping -c 5 180.214.47.65",
                    portName));
        */

        Thread.sleep(10000);
        p = Runtime.getRuntime().exec("sudo -n ip tuntap del dev port2");
        p.waitFor();
        log.debug("\"sudo -n ip tuntap del dev port2\" exited with: {}", p.exitValue());

        // Now clean up.
        //mmController.destroy();
        mgmt.delete(tenantURI.getPath());
        ovsdb.delBridge(brName);

        // 3) Create taps

        // 4) Create OVS datapaths and ports and set externalIds of
        // corresponding
        // vports. Use midolmanj's com.midokura.midolman.openvswitch.
        // OpenvSwitchDatabaseConnectionImpl class (it's defined in scala code).

        // 5) Create a ControllerTrampoline for each datapath. Initially, just
        // one
        // later probably 2. Later modify midolmanj's com.midokura.midolman
        // package to allow different ControllerTrampolines listening on
        // different ports.

        // 6) Ping a port.

        // 7) Tear down the OVS structures and taps.
    }
}
