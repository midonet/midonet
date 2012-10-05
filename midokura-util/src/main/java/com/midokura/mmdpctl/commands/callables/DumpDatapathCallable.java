package com.midokura.mmdpctl.commands.callables;

import com.midokura.mmdpctl.commands.results.DumpDatapathResult;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.sdn.dp.Datapath;
import com.midokura.sdn.dp.Flow;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Callable;

public class DumpDatapathCallable implements Callable<DumpDatapathResult> {
    private String datapathName;
    OvsDatapathConnection connection;

    public DumpDatapathCallable(OvsDatapathConnection connection, String datapathName) {
        this.datapathName = datapathName;
        this.connection = connection;
    }

    @Override
    public DumpDatapathResult call() throws Exception {
        try {
            Datapath datapath = connection.datapathsGet(datapathName).get();
            Set<Flow> flows = null;
            if (datapath != null) {
                flows  = connection.flowsEnumerate(datapath).get();
            } else {
                flows = Collections.emptySet();
            }
            return new DumpDatapathResult(datapath, flows);
        } catch (Exception e) {
            throw new Exception("Could not find datapath: " + datapathName);
        }
    }
}
