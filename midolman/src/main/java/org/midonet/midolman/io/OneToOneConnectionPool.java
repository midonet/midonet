/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.io;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.Bucket;

public class OneToOneConnectionPool implements DatapathConnectionPool {
    private Logger log = LoggerFactory.getLogger(this.getClass());

    public final String name;

    private MidolmanConfig config;

    private SelectorBasedDatapathConnection[] conns;

    public OneToOneConnectionPool(String name, int numChannels,
                                  MidolmanConfig config) {
        this.name = name;
        this.config = config;

        conns = new SelectorBasedDatapathConnection[numChannels];
        for (int i=0; i<numChannels; i++) {
            conns[i] =
                new SelectorBasedDatapathConnection(name + ".channel-" + i,
                                             config, false, Bucket.BOTTOMLESS);
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
