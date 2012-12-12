/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands.callables;

import java.util.concurrent.Callable;

import com.midokura.mmdpctl.commands.results.AddDatapathResult;
import com.midokura.odp.protos.OvsDatapathConnection;


public class AddDatapathCallable implements Callable<AddDatapathResult> {

    String datapathName;
    OvsDatapathConnection connection;

    public AddDatapathCallable(OvsDatapathConnection connection, String datapathName) {
        this.datapathName = datapathName;
        this.connection = connection;
    }


    @Override
    public AddDatapathResult call() throws Exception {
        try {
            connection.datapathsCreate(datapathName).get();
            return new AddDatapathResult();
        } catch (Exception e) {
            throw new Exception("Could not create datapath: " + datapathName);
        }
    }
}
