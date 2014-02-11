/*
* Copyright 2012 Midokura Europe SARL
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
                flows  = connection.futures.flowsEnumerate(datapath).get();
            } else {
                flows = Collections.emptySet();
            }
            return new DumpDatapathResult(datapath, flows);
        } catch (Exception e) {
            throw new Exception("Could not find datapath: " + datapathName);
        }
    }
}
