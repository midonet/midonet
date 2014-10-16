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

import org.midonet.netlink.Callback;
import org.midonet.odp.protos.OvsDatapathConnection;

public class MockManagedDatapathConnection implements ManagedDatapathConnection {
    private OvsDatapathConnection conn = null;

    public MockManagedDatapathConnection() {}

    public OvsDatapathConnection getConnection() {
        if (conn == null) {
            try {
                start();
            } catch (Exception e)  {
                throw new RuntimeException(e);
            }
        }
        return conn;
    }


    @Override
    public void start(Callback<Boolean> cb) {
        start();
        cb.onSuccess(true);
    }

    @Override
    public void start() {
        this.conn = OvsDatapathConnection.createMock();
    }

    public void stop() throws Exception {}
}
