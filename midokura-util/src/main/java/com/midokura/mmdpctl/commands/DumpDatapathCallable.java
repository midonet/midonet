package com.midokura.mmdpctl.commands;

import com.midokura.mmdpctl.netlink.NetlinkClient;
import com.midokura.mmdpctl.results.DumpDatapathResult;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.sdn.dp.Datapath;
import com.midokura.sdn.dp.Flow;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;

public class DumpDatapathCallable implements Callable<DumpDatapathResult> {
    private String datapathName;

    public DumpDatapathCallable(String datapathName) {
        this.datapathName = datapathName;
    }

    @Override
    public DumpDatapathResult call() throws Exception {
        OvsDatapathConnection connection = NetlinkClient.createDatapathConnection();

        Datapath datapath = connection.datapathsGet(datapathName).get();
        if (datapath != null) {
            Set<Flow> flows = connection.flowsEnumerate(datapath).get();
            return new DumpDatapathResult(flows);
        } else {
            return new DumpDatapathResult((Set<Flow>)Collections.emptySet());
        }
    }
}
