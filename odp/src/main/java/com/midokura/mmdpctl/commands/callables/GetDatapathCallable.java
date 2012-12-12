/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands.callables;

import java.util.Set;
import java.util.concurrent.Callable;

import com.midokura.mmdpctl.commands.results.GetDatapathResult;
import com.midokura.odp.Datapath;
import com.midokura.odp.Port;
import com.midokura.odp.protos.OvsDatapathConnection;


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
            Datapath datapath = connection.datapathsGet(datapathName).get();
            // get the datapath ports:
            Set<Port<?, ?>> ports = connection.portsEnumerate(datapath).get();
            return new GetDatapathResult(datapath, ports);
        } catch (Exception e) {
            throw new Exception("Could not find datapath: " + datapathName);
        }
    }
}
