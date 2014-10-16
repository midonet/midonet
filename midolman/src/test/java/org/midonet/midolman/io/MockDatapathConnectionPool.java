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

import java.util.Iterator;
import java.util.LinkedList;

import com.google.inject.Inject;

import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager;
import org.midonet.odp.protos.OvsDatapathConnection;


public class MockDatapathConnectionPool implements DatapathConnectionPool {
    @Inject
    private UpcallDatapathConnectionManager upcallConnManager;

    public MockDatapathConnectionPool() {}

    private OvsDatapathConnection conn() {
        return ((MockUpcallDatapathConnectionManager) upcallConnManager).
                conn().getConnection();
    }

    public Iterator<OvsDatapathConnection> getAll() {
        LinkedList<OvsDatapathConnection> li = new LinkedList<>();
        li.add(conn());
        return li.iterator();
    }

    public OvsDatapathConnection get(int hash) {
        return conn();
    }

    public void start() throws Exception {}

    public void stop() throws Exception {}
}
