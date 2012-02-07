/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.tools.process.ProcessHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 12/7/11
 * Time: 2:03 PM
 */
public abstract class AbstractSmokeTest {

    private final static Logger log = LoggerFactory
            .getLogger(AbstractSmokeTest.class);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                ProcessHelper
                        .newProcess("zkCli.sh -server 127.0.0.1:2181 rmr " +
                                " /smoketest")
                        .logOutput(log, "cleaning_zk")
                        .runAndWait();

            }
        });
    }

    protected static void removeTapWrapper(TapWrapper tap) {
        if (tap != null) {
            tap.remove();
        }
    }

    protected static void removeTenant(Tenant tenant) {
        if (null != tenant)
            tenant.delete();
    }

    protected static void stopMidolman(MidolmanLauncher mm) {
        if (null != mm)
            mm.stop();
    }

    protected static void stopMidolmanMgmt(MidolmanMgmt mgmt) {
        if (null != mgmt)
            mgmt.stop();
    }
}
