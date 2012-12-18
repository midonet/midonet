/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands;

import java.util.concurrent.Future;

import com.midokura.mmdpctl.commands.callables.DeleteDatapathCallable;
import com.midokura.mmdpctl.commands.results.DeleteDatapathResult;
import com.midokura.odp.protos.OvsDatapathConnection;


public class DeleteDatapathCommand extends Command<DeleteDatapathResult> {

    private String datapathName;

    public DeleteDatapathCommand(String datapathName) {
        this.datapathName = datapathName;
    }

    public Future<DeleteDatapathResult> execute(OvsDatapathConnection connection) {
        return run(new DeleteDatapathCallable(connection, datapathName));
    }

}
