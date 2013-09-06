/*
 * Copyright 2011 Midokura Europe SARL
 */
package org.midonet.functional_test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.hamcrest.Matcher;
import org.junit.Ignore;
import org.junit.Test;

import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.DhcpSubnet;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.functional_test.vm.HypervisorType;
import org.midonet.functional_test.vm.VMController;
import org.midonet.functional_test.vm.libvirt.LibvirtHandler;
import org.midonet.packets.IPv4Addr;
import org.midonet.tools.timed.Timed;
import org.midonet.util.lock.LockHelper;
import org.midonet.util.ssh.SshHelper;


import static org.midonet.hamcrest.RegexMatcher.matchesRegex;
import static org.midonet.functional_test.FunctionalTestsHelper.destroyVM;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static org.midonet.util.Waiters.sleepBecause;
import static org.midonet.util.Waiters.waitFor;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test suite that exercises the DHCP options configuration across a LAN.
 */
@Ignore
public class BridgeDHCPTest extends TestBase {

    static TapWrapper tap;
    static Bridge bridge;

    static String vmHostName = "br-dhcp-test-host";

    static VMController vm;

    static LockHelper.Lock lock;

    @Override
    protected void setup() {
        bridge = apiClient.addBridge()
            .tenantId("bridge-dhcp-test").name("br1").create();
        BridgePort port1 = bridge.addExteriorPort().create();
        tap = new TapWrapper("vmTap");
        thisHost.addHostInterfacePort()
            .interfaceName(tap.getName())
            .portId(port1.getId()).create();

        probe.expectMsgClass(Duration.create(10, TimeUnit.SECONDS),
            LocalPortActive.class);

        BridgePort port2 = bridge.addExteriorPort().create();
        // Bind the internal 'local' port to the second exterior port.
        String localName = "midonet";
        log.debug("Bind datapath's local port to bridge's exterior port.");
        thisHost.addHostInterfacePort()
            .interfaceName(localName)
            .portId(port2.getId()).create();

        DhcpSubnet dhcpSubnet = bridge.addDhcpSubnet()
            .defaultGateway("192.168.231.3")
            .subnetPrefix("192.168.231.1")
            .subnetLength(24)
            .create();
        dhcpSubnet.addDhcpHost()
            .ipAddr("192.168.231.10")
            .macAddr("02:AA:BB:BB:AA:02")
            .create();

        // XXX TODO(pino): assing an IP address to the internal port.
        IPv4Addr ip2 = IPv4Addr.fromString("192.168.231.3");

        tap.closeFd();

        LibvirtHandler handler =
            LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        handler.setTemplate("basic_template_x86_64");

        vm = handler.newDomain()
                    .setDomainName("test_br_dhcp")
                    .setHostName(vmHostName)
                    .setNetworkDevice(tap.getName())
                    .build();

        vm.startup();
        try {
            sleepBecause("Give the machine a little bit of time to bootup", 5);
        } catch (Exception e){

        }
    }

    @Override
    protected void teardown() {
        vm.shutdown();
        try {
            waitFor("the VM to shutdown", TimeUnit.SECONDS.toMillis(3), 50,
                new Timed.Execution<Object>() {
                    @Override
                    protected void _runOnce() throws Exception {
                        setCompleted(!vm.isRunning());
                    }
                });
        } catch (Exception e) {

        }
        destroyVM(vm);
        removeTapWrapper(tap);
    }

    @Test
    public void testMtu() throws Exception {
        assertThatCommandOutput("the host should have our chosen name",
            "hostname", is(vmHostName));

        assertThatCommandOutput("displays the new MTU value",
                                "ip link show eth0 | grep mtu",
                                matchesRegex(".+mtu 1450.+"));
    }

    protected void assertThatCommandOutput(String message,
                                           final String command,
                                           final Matcher<String> resultMatcher)
        throws Exception {
        String commandResult =
            waitFor("successful remote execution of: " + command,
                    TimeUnit.MINUTES.toMillis(2), 50,
                    new Timed.Execution<String>() {
                        @Override
                        protected void _runOnce() throws Exception {
                            try {
                                String result =
                                    SshHelper.newRemoteCommand(command)
                                             .onHost("192.168.231.10")
                                             .withCredentials(
                                                 "ubuntu", "ubuntu")
                                             .run((int) getRemainingTime());

                                setResult(result.trim());
                                setCompleted(resultMatcher.matches(getResult()));
                            } catch (IOException e) {
                                // try again later
                            }
                        }
                    });

        assertThat(message, commandResult, resultMatcher);
    }

}
