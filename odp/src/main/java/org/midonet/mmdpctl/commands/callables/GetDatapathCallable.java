/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
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
