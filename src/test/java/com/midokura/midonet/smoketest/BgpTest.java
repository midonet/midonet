/*
* Copyright 2011 Midokura Europe SARL
*/

package com.midokura.midonet.smoketest;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.*;
import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import static com.midokura.tools.process.ProcessHelper.newProcess;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/28/11
 * Time: 1:34 PM
 */
public class BgpTest {

    private final static Logger log = LoggerFactory.getLogger(BgpTest.class);

    static Tenant tenant;
    static TapPort bgpPort;
    static Bgp bgp;
    static MidolmanMgmt mgmt;

    static VMController bgpPeerVm;

    static OpenvSwitchDatabaseConnection ovsdb;

    static String bgpPortPortName = "bgpPeerPort";

    static NetworkInterface ethernetDevice;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

//        createPrivateBGPPeersNetworkDevice();

        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
            "Open_vSwitch", "127.0.0.1", 12344);
//
        mgmt = new MockMidolmanMgmt(false);

        tenant = new Tenant.Builder(mgmt).setName("tenant1").build();

        Router router = tenant.addRouter().setName("rtr1").build();

        bgpPort = router.addPort(ovsdb)
            .setNetworkLength(24)
            .setDestination("10.10.173.1")
            .setOVSPortName(bgpPortPortName)
            .buildTap();

        Bgp bgp = bgpPort.addBgp()
            .setLocalAs(543)
            .setPeer(345, "10.10.173.2")
            .build();

        AdRoute advertisedRoute = bgp.addAdvertisedRoute("14.128.23.0", 27);

        LibvirtHandler libvirtHandler = LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        libvirtHandler.setTemplate("basic_template_x86_64");

        // the bgp machine builder will create a vm bound to a local tap and
        // assign the following address 10.10.173.2/24
        // we will create a tap device on the local machine with the
        // 10.0.173.1/24 address so we can communicate with it

        bgpPeerVm = libvirtHandler
            .newBgpDomain()
                .setDomainName("bgpPeer")
                .setHostName("bgppeer")
                .setNetworkDevice(bgpPort.getName())
                .setLocalAS(345)
                .setPeerAS(543)
                .build();

//        Thread.sleep(1000);
    }

    private static void createPrivateBGPPeersNetworkDevice() {
        newProcess(
            String.format("sudo -n ip tuntap add dev %s mode tap", bgpPort))
            .logOutput(log, "dev_create@" + bgpPort)
            .runAndWait();

        newProcess(
            String.format("sudo -n ip link set %s arp on mtu 1300 multicast off up", bgpPort))
            .logOutput(log, "link_config@" + bgpPort)
            .runAndWait();

        newProcess(
            String.format("sudo -n ip addr add 10.10.173.1/24 broadcast 10.10.173.255 dev %s ", bgpPort))
            .logOutput(log, "addr_config@" + bgpPort)
            .runAndWait();
    }

    private static void deletePrivateBGPPeersNetworkDevice() {
        newProcess(
            String.format("sudo -n ip tuntap del dev %s mode tap", bgpPort))
            .logOutput(log, "dev_create@" + bgpPort)
            .runAndWait();
    }

    private static NetworkInterface findLocalEthernetDeviceName() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();


        NetworkInterface localNetworkInterface = null;

        while (interfaces.hasMoreElements()) {
            NetworkInterface netInterface =  interfaces.nextElement();

            if ( netInterface.isLoopback() || netInterface.isVirtual()
                || ! netInterface.getName().startsWith("eth"))
            {
                continue;
            }

            Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {

                InetAddress inetAddress = addresses.nextElement();

                if ( inetAddress instanceof Inet4Address )
                {
                    if ( localNetworkInterface == null ||
                        localNetworkInterface.getName().compareTo(netInterface.getName()) > 0 )
                    {
                        localNetworkInterface = netInterface;
                    }
                }
            }
        }

        return localNetworkInterface;
    }

    @AfterClass
    public static void tearDown() {

/*
        deletePrivateBGPPeersNetworkDevice();

        if ( ovsdb.hasBridge("smoke-br") ) {
            ovsdb.delBridge("smoke-br");
        }

        try {
            mgmt.deleteTenant("tenant1");
        } catch (Exception e) {
        }

*/
    }

    @Test
    public void testBgpConfiguration() throws Exception {
//        int a = 10;
    }
}
