package com.midokura.mmdpctl.commands.callables;

import com.midokura.mmdpctl.netlink.NetlinkClient;
import com.midokura.mmdpctl.commands.results.AddDatapathResult;
import com.midokura.netlink.protos.OvsDatapathConnection;
import java.util.concurrent.Callable;

public class AddDatapathCallable implements Callable<AddDatapathResult> {

    String datapathName;

    public AddDatapathCallable(String datapathName) {
        this.datapathName = datapathName;
    }


    @Override
    public AddDatapathResult call() throws Exception {
        OvsDatapathConnection connection = null;
        try {
            connection = NetlinkClient.createDatapathConnection();
        } catch (Exception e) {
            throw new Exception("Could not connect to Netlink.");
        }

        try {
            connection.datapathsCreate(datapathName).get();
            return new AddDatapathResult();
        } catch (Exception e) {
            throw new Exception("Could not create datapath: " + datapathName);
        }
    }
}
