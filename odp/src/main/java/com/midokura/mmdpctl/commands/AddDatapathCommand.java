/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands;

import java.util.concurrent.Future;

import com.midokura.mmdpctl.commands.callables.AddDatapathCallable;
import com.midokura.mmdpctl.commands.results.AddDatapathResult;
import com.midokura.odp.protos.OvsDatapathConnection;


public class AddDatapathCommand extends Command<AddDatapathResult> {

    private String datapathName;

    public AddDatapathCommand(String datapathName) {
        this.datapathName = datapathName;
    }

    public Future<AddDatapathResult> execute(OvsDatapathConnection connection) {
        return run(new AddDatapathCallable(connection, datapathName));
    }

}
