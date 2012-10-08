package com.midokura.mmdpctl.commands;

import com.midokura.mmdpctl.commands.callables.AddDatapathCallable;
import com.midokura.mmdpctl.commands.results.AddDatapathResult;
import com.midokura.netlink.protos.OvsDatapathConnection;

import java.util.concurrent.Future;

public class AddDatapathCommand extends Command<AddDatapathResult> {

    private String datapathName;

    public AddDatapathCommand(String datapathName) {
        this.datapathName = datapathName;
    }

    public Future<AddDatapathResult> execute(OvsDatapathConnection connection) {
        return run(new AddDatapathCallable(connection, datapathName));
    }

}
