/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.callables;

import java.util.Set;
import java.util.concurrent.Callable;

import org.midonet.mmdpctl.commands.results.ListDatapathsResult;
import org.midonet.odp.Datapath;
import org.midonet.odp.protos.OvsDatapathConnection;


public class ListDatapathsCallable implements Callable<ListDatapathsResult> {

    OvsDatapathConnection connection;

    public ListDatapathsCallable(OvsDatapathConnection connection) {
        this.connection = connection;
    }

    @Override
    public ListDatapathsResult call() throws Exception {
        Set<Datapath> datapaths = connection.futures.datapathsEnumerate().get();
        return new ListDatapathsResult(datapaths);
    }
}
