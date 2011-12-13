/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.smoketest;

import com.midokura.midonet.smoketest.topology.InternalPort;
import com.midokura.midonet.smoketest.topology.Port;
import com.midokura.midonet.smoketest.topology.TapPort;
import com.midokura.midonet.smoketest.topology.TapWrapper;
import com.midokura.midonet.smoketest.topology.Tenant;
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

    private final static Logger log =
        LoggerFactory.getLogger(AbstractSmokeTest.class);


    protected static void removePort(Port port) {
        if (port != null) {
            port.delete();
        }
    }

    protected static void removeTapWrapper(TapWrapper tap) {
        if (tap != null) {
            tap.remove();
        }
    }

    protected static void resetZooKeeperState(Logger log) {
        ProcessHelper
            .newProcess("zkCli.sh -server 127.0.0.1:2181 rmr /test/midolman-mgmt")
            .logOutput(log, "cleaning_zk")
            .runAndWait();
    }

    protected static void removeTenant(Tenant tenant) {
        try {
            tenant.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
