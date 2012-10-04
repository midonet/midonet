package com.midokura.mmdpctl.commands.callables;

import com.midokura.mmdpctl.netlink.NetlinkClient;
import com.midokura.mmdpctl.commands.results.DeleteDatapathResult;
import com.midokura.netlink.protos.OvsDatapathConnection;

import java.util.concurrent.Callable;

public class DeleteDatapathCallable implements Callable<DeleteDatapathResult> {

    String datapathName;

    public DeleteDatapathCallable(String datapathName) {
        this.datapathName = datapathName;
    }


    @Override
    public DeleteDatapathResult call() throws Exception {
        OvsDatapathConnection connection = null;
        try {
            connection = NetlinkClient.createDatapathConnection();
        } catch (Exception e) {
            throw new Exception("Could not connect to Netlink.");
        }

        try {
            connection.datapathsDelete(datapathName).get();
            return new DeleteDatapathResult();
        } catch (Exception e) {
            throw new Exception("Could not delete datapath: " + datapathName);
        }
    }
}
