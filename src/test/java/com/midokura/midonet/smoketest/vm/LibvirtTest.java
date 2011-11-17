package com.midokura.midonet.smoketest.vm;

import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/17/11
 * Time: 12:11 PM
 */
public class LibvirtTest {

    private final static Logger log = LoggerFactory.getLogger(LibvirtTest.class);

    static LibvirtHandler libvirtHandler;
    static boolean failIfInvalidConfiguration;
    private static final String LIBVIRT_TESTS_FAIL_INVALID_CONFIGURATION = "libvirt.tests.fail.invalid.configuration";

    @BeforeClass
    public static void initializeHandler() {
        libvirtHandler = LibvirtHandler.forHypervisor(HypervisorType.Qemu);
        failIfInvalidConfiguration = Boolean.parseBoolean(System.getProperty(LIBVIRT_TESTS_FAIL_INVALID_CONFIGURATION, "true"));
    }

    @Test
    public void testHannibal() throws Exception {

        if ( ! checkRuntimeConfiguration() ) {
            return;
        }

        libvirtHandler.setTemplate("basic_template_x86_64");

        VMController vmController = libvirtHandler.newDomain()
                .setHostName("testvm")
                .setDomainName("testdomain")
                .setNetworkDevice("tap1")
                .build();

        assertThat(vmController, is(notNullValue()));
        assertThat(vmController.isRunning(), equalTo(false));
        vmController.startup();
        assertThat(vmController.isRunning(), equalTo(true));
        vmController.shutdown();
        assertThat(vmController.isRunning(), equalTo(false));
        vmController.destroy();
    }

    private boolean checkRuntimeConfiguration() {
        if ( libvirtHandler.isRuntimeConfigurationValid() ) {
            return true;
        }

        String message = "The runtime libvirt configuration is invalid. See http://url for instructions on how to setup a proper runtime configuration.";
        if ( failIfInvalidConfiguration ) {
            fail(message);
        } else {
            log.error(message);
        }

        return false;
    }
}
