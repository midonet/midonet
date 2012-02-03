/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test.vm;

import com.midokura.midonet.functional_test.vm.libvirt.LibvirtHandler;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;

import static junit.framework.Assert.fail;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/17/11
 * Time: 12:11 PM
 */
public abstract class AbstractLibvirtTest {

    private final static Logger log = LoggerFactory.getLogger(AbstractLibvirtTest.class);

    static LibvirtHandler libvirtHandler;
    static boolean failIfInvalidConfiguration;
    private static final String LIBVIRT_TESTS_FAIL_INVALID_CONFIGURATION = "libvirt.tests.fail.invalid.configuration";

    private static final String SMOKE_TEST_VM_SUBSYSTEM_RUNTIME_CONFIGURATION_URL =
            "https://sites.google.com/a/midokura.jp/wiki/midonet/java-smoke-test/vm-management-subsystem";

    @BeforeClass
    public static void initializeHandler() {
        libvirtHandler = LibvirtHandler.forHypervisor(HypervisorType.Qemu);
        failIfInvalidConfiguration = Boolean.parseBoolean(System.getProperty(LIBVIRT_TESTS_FAIL_INVALID_CONFIGURATION, "true"));
    }


    protected boolean checkRuntimeConfiguration() {
        if ( libvirtHandler.isRuntimeConfigurationValid() ) {
            return true;
        }

        String message = MessageFormat.format(
                "The runtime libvirt configuration is invalid. See {0} for instructions on how to setup a proper runtime configuration.",
                SMOKE_TEST_VM_SUBSYSTEM_RUNTIME_CONFIGURATION_URL);

        if ( failIfInvalidConfiguration ) {
            fail(message);
        } else {
            log.error(message);
        }

        return false;
    }
}
