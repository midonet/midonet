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

import java.util.Set;
import java.util.concurrent.Callable;

import org.midonet.mmdpctl.commands.results.GetDatapathResult;
import org.midonet.odp.Datapath;
import org.midonet.odp.DpPort;
import org.midonet.odp.protos.OvsDatapathConnection;


public class GetDatapathCallable implements Callable<GetDatapathResult> {

    private String datapathName;
    private OvsDatapathConnection connection;

    public GetDatapathCallable(OvsDatapathConnection connection, String datapathName) {
        this.datapathName = datapathName;
        this.connection = connection;
    }

    @Override
    public GetDatapathResult call() throws Exception {
        try {
            Datapath datapath = connection.futures.datapathsGet(datapathName).get();
            // get the datapath ports:
            Set<DpPort> ports = connection.futures.portsEnumerate(datapath).get();
            return new GetDatapathResult(datapath, ports);
        } catch (Exception e) {
            throw new Exception("Could not find datapath: " + datapathName);
        }
    }
}
