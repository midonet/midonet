/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.smoketest.vm;

import com.midokura.midonet.smoketest.utils.Tap;
import org.junit.Test;

import static com.midokura.tools.hamcrest.RegexMatcher.matchesRegex;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/17/11
 * Time: 2:38 PM
 */
public class LibvirtTest extends AbstractLibvirtTest {

    @Test
    public void testDomainCreation() throws Exception {

        if ( ! checkRuntimeConfiguration() ) {
            return;
        }

        libvirtHandler.setTemplate("basic_template_x86_64");

        String testDomainName = "testdomain51";
        String testHostname = "testvm";

        VMController vmController = libvirtHandler.newDomain()
                .setHostName(testHostname)
                .setDomainName(testDomainName)
//                .setNetworkDevice("tap1")
                .build();

        assertThat("The controller for the new VM should be properly created!", vmController, is(notNullValue()));
        assertThat("The new domain should have the proper domain name", vmController.getDomainName(), equalTo(testDomainName));
        assertThat("The new domain should have the proper host name", vmController.getHostName(), equalTo(testHostname));
        assertThat("The VM should have a non null MAC address", vmController.getNetworkMacAddress(), is(notNullValue()));
        assertThat("The VM should have a proper MAC address", vmController.getNetworkMacAddress(), matchesRegex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"));
        assertThat("The domain should not be running by default", vmController.isRunning(), equalTo(false));

        // just destroy the domain
        vmController.destroy();
    }

    @Test
    public void testStartupShutdown() throws Exception {
        if ( ! checkRuntimeConfiguration() ) {
            return;
        }

        libvirtHandler.setTemplate("basic_template_x86_64");

        String testDomainName = "testdomain52";
        String testHostname = "testvm";

        VMController vmController = libvirtHandler.newDomain()
                .setHostName(testHostname)
                .setDomainName(testDomainName)
//                .setNetworkDevice("tap1")
                .build();

        assertThat("The controller for the new VM should be properly created!", vmController, is(notNullValue()));
        assertThat("The domain should not be running by default", vmController.isRunning(), equalTo(false));

        vmController.startup();
        assertThat("The domain should be running after starting up", vmController.isRunning(), equalTo(true));

        vmController.shutdown();
        assertThat("The domain should not be running after shutdown", vmController.isRunning(), equalTo(false));

        // just destroy the domain
        vmController.destroy();
    }

    @Test
    public void testTwoMachines() throws Exception {
        if ( ! checkRuntimeConfiguration() ) {
            return;
        }

        libvirtHandler.setTemplate("basic_template_x86_64");

        String testDomainName = "testdomain_";
        String testHostname = "testvm_";

        VMController firstVm = libvirtHandler.newDomain()
                .setHostName(testHostname + "1")
                .setDomainName(testDomainName + "1")
                .build();

        assertThat("The controller for the first VM should be properly created!", firstVm, is(notNullValue()));
        assertThat("The domain should not be running by default", firstVm.isRunning(), equalTo(false));

        VMController secondVm = libvirtHandler.newDomain()
                .setHostName(testHostname + "2")
                .setDomainName(testDomainName + "2")
                .build();

        assertThat("The controller for the second VM should be properly created!", secondVm, is(notNullValue()));
        assertThat("The domain should not be running by default", secondVm.isRunning(), equalTo(false));

        firstVm.startup();
        assertThat("The domain should be running after starting up", firstVm.isRunning(), equalTo(true));

        secondVm.startup();
        assertThat("The domain should be running after starting up", secondVm.isRunning(), equalTo(true));

        firstVm.shutdown();
        assertThat("The domain should not be running after shutdown", firstVm.isRunning(), equalTo(false));

        secondVm.shutdown();
        assertThat("The domain should not be running after shutdown", firstVm.isRunning(), equalTo(false));

        // just destroy the domain
        firstVm.destroy();
        secondVm.destroy();
    }
}
