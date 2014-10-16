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

import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.Callback;
import org.midonet.odp.protos.OvsDatapathConnection;

public class TrivialDatapathConnection implements ManagedDatapathConnection {
    private Logger log = LoggerFactory.getLogger(this.getClass());


    private OvsDatapathConnection conn = null;

    public TrivialDatapathConnection(OvsDatapathConnection conn) {
        this.conn = conn;
    }

    @Override
    public OvsDatapathConnection getConnection() {
        return conn;
    }

    @Override
    public void start() throws InterruptedException, ExecutionException {
        conn.futures.initialize().get();
    }

    @Override
    public void start(Callback<Boolean> cb) {
        conn.initialize(cb);
    }

    @Override
    public void stop() throws Exception {}
}
