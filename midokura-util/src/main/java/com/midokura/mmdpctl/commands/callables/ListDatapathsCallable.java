/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands.callables;

import com.midokura.mmdpctl.commands.results.ListDatapathsResult;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.sdn.dp.Datapath;

import java.util.Set;
import java.util.concurrent.Callable;

public class ListDatapathsCallable implements Callable<ListDatapathsResult> {

    OvsDatapathConnection connection;

    public ListDatapathsCallable(OvsDatapathConnection connection) {
        this.connection = connection;
    }

    @Override
    public ListDatapathsResult call() throws Exception {
        Set<Datapath> datapaths = connection.datapathsEnumerate().get();
        return new ListDatapathsResult(datapaths);
    }
}
