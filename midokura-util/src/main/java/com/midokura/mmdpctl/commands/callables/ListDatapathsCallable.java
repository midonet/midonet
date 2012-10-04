package com.midokura.mmdpctl.commands.callables;

import com.midokura.mmdpctl.netlink.NetlinkClient;
import com.midokura.mmdpctl.commands.results.ListDatapathsResult;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.sdn.dp.Datapath;

import java.util.Set;
import java.util.concurrent.Callable;

public class ListDatapathsCallable implements Callable<ListDatapathsResult> {

    @Override
    public ListDatapathsResult call() throws Exception {
        OvsDatapathConnection connection = NetlinkClient.createDatapathConnection();
        Set<Datapath> datapaths = connection.datapathsEnumerate().get();
        return new ListDatapathsResult(datapaths);
    }
}
