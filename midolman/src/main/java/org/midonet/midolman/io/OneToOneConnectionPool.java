/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.datapath.DatapathModule.SIMULATION_THROTTLING_GUARD;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.throttling.ThrottlingGuard;


public class OneToOneConnectionPool implements DatapathConnectionPool {
    private Logger log = LoggerFactory.getLogger(OneToOneConnectionPool.class);

    public final String name;

    @Inject
    private MidolmanConfig config;

    @Inject
    private Reactor reactor;

    @Inject
    @SIMULATION_THROTTLING_GUARD
    private ThrottlingGuard throttler;

    private DualSelectorDatapathConnection[] conns;

    public OneToOneConnectionPool(String name, int numChannels) {
        this.name = name;

        conns = new DualSelectorDatapathConnection[numChannels];
        for (int i=0; i<numChannels; i++) {
            conns[i] = new DualSelectorDatapathConnection(this.name + ".channel-" + i,
                    reactor, throttler, config);
        }
    }

    public Iterator<OvsDatapathConnection> getAll() {
        List<OvsDatapathConnection> li = new ArrayList<>(conns.length);
        for (ManagedDatapathConnection managed: conns)
            li.add(managed.getConnection());
        return li.iterator();
    }

    public OvsDatapathConnection get(int hash) {
        return conns[Math.abs(hash) % conns.length].getConnection();
    }

    public void start() throws Exception {
        log.info("Starting datapath connection pool {} with {} channels",
                 name, conns.length);
        for (ManagedDatapathConnection managed: conns)
            managed.start();
    }

    public void stop() throws Exception {
        log.info("Stopping datapath connection pool {}", name);
        for (int i=0; i<conns.length; i++) {
            conns[i].stop();
        }
    }

}
