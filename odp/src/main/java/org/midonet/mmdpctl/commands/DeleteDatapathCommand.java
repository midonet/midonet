/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands;

import java.util.concurrent.Future;

import org.midonet.mmdpctl.commands.callables.DeleteDatapathCallable;
import org.midonet.mmdpctl.commands.results.DeleteDatapathResult;
import org.midonet.odp.protos.OvsDatapathConnection;


public class DeleteDatapathCommand extends Command<DeleteDatapathResult> {

    private String datapathName;

    public DeleteDatapathCommand(String datapathName) {
        this.datapathName = datapathName;
    }

    public Future<DeleteDatapathResult> execute(OvsDatapathConnection connection) {
        return run(new DeleteDatapathCallable(connection, datapathName));
    }

}
