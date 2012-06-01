/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test.vm;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.midokura.util.process.ProcessHelper;
import static com.midokura.tools.hamcrest.RegexMatcher.matchesRegex;

public class LibvirtTest extends AbstractLibvirtTest {

    private final static Logger log = LoggerFactory.getLogger(LibvirtTest.class);

    @Test
    public void testDomainCreation() throws Exception {

        if ( ! checkRuntimeConfiguration() ) {
            return;
        }

        libvirtHandler.setTemplate("basic_template_x86_64");

        String testDomainName = "testdomain51";
        String testHostname = "testvm";

        VMController vm = null;
        try {
            vm = libvirtHandler.newDomain()
                    .setHostName(testHostname)
                    .setDomainName(testDomainName)
                    .build();

            assertThat("The controller for the new VM should be properly created!",
                          vm, is(notNullValue()));

            assertThat("The new domain should have the proper domain name",
                          vm.getDomainName(), equalTo(testDomainName));

            assertThat("The new domain should have the proper host name",
                          vm.getHostName(), equalTo(testHostname));

            assertThat("The VM should have a non null MAC address",
                          vm.getNetworkMacAddress(), is(notNullValue()));

            assertThat("The VM should have a proper MAC address",
                          vm.getNetworkMacAddress(),
                          matchesRegex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"));

            assertThat("The domain should not be running by default",
                          vm.isRunning(), equalTo(false));
        } finally {
            if ( vm != null ) {
                // just destroy the domain
                vm.destroy();
            }
        }
    }

    @Test
    public void testStartupShutdown() throws Exception {

        if ( ! checkRuntimeConfiguration() ) {
            return;
        }

        libvirtHandler.setTemplate("basic_template_x86_64");

        String testDomainName = "testdomain52";
        String testHostname = "testvm";

        VMController vm =  null;
        try {
            vm = libvirtHandler.newDomain()
                    .setHostName(testHostname)
                    .setDomainName(testDomainName)
    //                .setNetworkDevice("tap1")
                    .build();

            assertThat("The controller for the new VM should be properly created!",
                          vm, is(notNullValue()));
            assertThat("The domain should not be running by default",
                          vm.isRunning(), equalTo(false));

            vm.startup();
            assertThat("The domain should be running after starting up",
                          vm.isRunning(), equalTo(true));

            vm.shutdown();
            assertThat("The domain should not be running after shutdown",
                          vm.isRunning(), equalTo(false));
        } finally {
            if ( vm != null ) {
                // just destroy the domain
                vm.destroy();
            }
        }
    }

    @Test
    public void testTwoMachines() throws Exception {
        if ( ! checkRuntimeConfiguration() ) {
            return;
        }

        libvirtHandler.setTemplate("basic_template_x86_64");

        String testDomainName = "testdomain_";
        String testHostname = "testvm_";

        VMController firstVm = null;
        VMController secondVm = null;

        try {
            firstVm = libvirtHandler.newDomain()
                    .setHostName(testHostname + "1")
                    .setDomainName(testDomainName + "1")
                    .build();

            assertThat("The controller for the first VM should be properly created!",
                          firstVm, is(notNullValue()));

            assertThat("The domain should not be running by default",
                          firstVm.isRunning(), equalTo(false));


            secondVm = libvirtHandler.newDomain()
                    .setHostName(testHostname + "2")
                    .setDomainName(testDomainName + "2")
                    .build();

            assertThat("The controller for the second VM should be properly created!",
                          secondVm, is(notNullValue()));

            assertThat("The domain should not be running by default",
                          secondVm.isRunning(), equalTo(false));

            firstVm.startup();
            assertThat("The domain should be running after starting up",
                          firstVm.isRunning(), equalTo(true));

            secondVm.startup();
            assertThat("The domain should be running after starting up",
                          secondVm.isRunning(), equalTo(true));

            firstVm.shutdown();
            assertThat("The domain should not be running after shutdown",
                          firstVm.isRunning(), equalTo(false));

            secondVm.shutdown();
            assertThat("The domain should not be running after shutdown",
                          firstVm.isRunning(), equalTo(false));
        } finally {
            // just destroy the domain
            if ( firstVm != null ) {
                firstVm.destroy();
            }


            if (secondVm != null) {
                secondVm.destroy();
            }

        }

    }

    @Test
    public void testBindToTapDevice() throws Exception {

        String portName = "customTapPort";

        // create a tap port
        runCommandAndWait(
                String.format("sudo -n ip tuntap add dev %s mode tap", portName));

        // set link up to that port
        runCommandAndWait(
                String.format("sudo -n ip link set dev %s up", portName));

        libvirtHandler.setTemplate("basic_template_x86_64");

        VMController vm = null;
        try {

            vm = libvirtHandler.newDomain()
                .setDomainName("test_tap_bind")
                .setNetworkDevice(portName)
                .build();

            vm.startup();
            assertThat("The domain should be running after starting up",
                          vm.isRunning(), equalTo(true));
        } finally {
            if ( vm != null ) {
                vm.destroy();
            }

            runCommandAndWait(
                String.format("sudo -n ip tuntap del dev %s mode tap", portName));
        }
    }

    protected void runCommandAndWait(String command) throws Exception {
        ProcessHelper
            .newProcess(command)
            .logOutput(log, command)
            .runAndWait();
    }
}
