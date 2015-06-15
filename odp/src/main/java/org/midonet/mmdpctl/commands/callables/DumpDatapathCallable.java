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
package org.midonet.mmdpctl.commands.callables;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;

import org.midonet.mmdpctl.commands.results.DumpDatapathResult;
import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;
import org.midonet.odp.protos.OvsDatapathConnection;


public class DumpDatapathCallable implements Callable<DumpDatapathResult> {
    private String datapathName;
    OvsDatapathConnection connection;

    public DumpDatapathCallable(OvsDatapathConnection connection, String datapathName) {
        this.datapathName = datapathName;
        this.connection = connection;
    }

    @Override
    public DumpDatapathResult call() throws Exception {
        try {
            Datapath datapath = connection.futures.datapathsGet(datapathName).get();
            Set<Flow> flows = null;
            if (datapath != null) {
                flows = connection.futures.flowsEnumerate(datapath).get();
            } else {
                flows = Collections.emptySet();
            }
            return new DumpDatapathResult(datapath, flows);
        } catch (Exception e) {
            throw new Exception("Could not find datapath: " + datapathName);
        }
    }
}
