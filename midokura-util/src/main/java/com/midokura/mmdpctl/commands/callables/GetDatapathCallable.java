package com.midokura.mmdpctl.commands.callables;

import com.midokura.mmdpctl.netlink.NetlinkClient;
import com.midokura.mmdpctl.commands.results.GetDatapathResult;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.sdn.dp.Datapath;
import com.midokura.sdn.dp.Port;

import java.util.Set;
import java.util.concurrent.Callable;

public class GetDatapathCallable implements Callable<GetDatapathResult> {

    private String datapathName;

    public GetDatapathCallable(String datapathName) {
        this.datapathName = datapathName;
    }

    @Override
    public GetDatapathResult call() throws Exception {

        OvsDatapathConnection connection = null;
        try {
            connection = NetlinkClient.createDatapathConnection();
        } catch (Exception e) {
            throw new Exception("Could not connect to Netlink.");
        }

        try {
            Datapath datapath = connection.datapathsGet(datapathName).get();
            // get the datapath ports:
            Set<Port<?, ?>> ports = connection.portsEnumerate(datapath).get();
            return new GetDatapathResult(datapath, ports);
        } catch (Exception e) {
            throw new Exception("Could not find datapath: " + datapathName);
        }
    }
}
