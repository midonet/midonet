/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import com.google.inject.Injector;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.junit.Test;
import sun.jvmstat.monitor.MonitorException;

/**
 * Date: 5/30/12
 */
public class MonitoringTest {

    @Test
    public void test() throws MonitorException, URISyntaxException, IOException,
                              InterruptedException, ConfigurationException {
        String curr = new File(".").getAbsolutePath();
        String configFilePath = "./midolmanj/conf/midolman.conf";
        Injector injector;
        HierarchicalConfiguration config = new HierarchicalINIConfiguration(configFilePath);
        MonitoringAgent.bootstrapMonitoring(config, null);
        Thread.sleep(200000);
    }
}
