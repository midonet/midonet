package com.midokura.mmdpctl.commands.callables;

import com.midokura.mmdpctl.commands.results.AddDatapathResult;
import com.midokura.netlink.protos.OvsDatapathConnection;

import java.util.concurrent.Callable;

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
