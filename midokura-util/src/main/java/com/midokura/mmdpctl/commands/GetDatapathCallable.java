package com.midokura.mmdpctl.commands;

import com.midokura.mmdpctl.netlink.NetlinkClient;
import com.midokura.mmdpctl.results.GetDatapathResult;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.sdn.dp.Datapath;

import java.util.concurrent.Callable;

class GetDatapathCallable implements Callable<GetDatapathResult> {

    private String datapathName;

    public GetDatapathCallable(String datapathName) {
        this.datapathName = datapathName;
    }

    @Override
    public GetDatapathResult call() throws Exception {
        OvsDatapathConnection connection = NetlinkClient.createDatapathConnection();

        Datapath datapath = connection.datapathsGet(datapathName).get();
        return new GetDatapathResult(datapath);
    }
}
