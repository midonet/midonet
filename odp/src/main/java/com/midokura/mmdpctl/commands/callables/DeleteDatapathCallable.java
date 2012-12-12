/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands.callables;

import java.util.concurrent.Callable;

import com.midokura.mmdpctl.commands.results.DeleteDatapathResult;
import com.midokura.odp.protos.OvsDatapathConnection;


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
            connection.datapathsDelete(datapathName).get();
            return new DeleteDatapathResult();
        } catch (Exception e) {
            throw new Exception("Could not delete datapath: " + datapathName);
        }
    }
}
