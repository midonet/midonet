/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands.callables;

import java.util.concurrent.Callable;

import org.midonet.mmdpctl.commands.results.DeleteDatapathResult;
import org.midonet.odp.protos.OvsDatapathConnection;


public class DeleteDatapathCallable implements Callable<DeleteDatapathResult> {

    String datapathName;
    OvsDatapathConnection connection;

    public DeleteDatapathCallable(OvsDatapathConnection connection, String datapathName) {
        this.datapathName = datapathName;
        this.connection = connection;
    }

    @Override
    public DeleteDatapathResult call() throws Exception {
        try {
            connection.futures.datapathsDelete(datapathName).get();
            return new DeleteDatapathResult();
        } catch (Exception e) {
            throw new Exception("Could not delete datapath: " + datapathName);
        }
    }
}
