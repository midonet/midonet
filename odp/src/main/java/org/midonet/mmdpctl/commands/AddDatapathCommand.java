/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands;

import java.util.concurrent.Future;

import org.midonet.mmdpctl.commands.callables.AddDatapathCallable;
import org.midonet.mmdpctl.commands.results.AddDatapathResult;
import org.midonet.odp.protos.OvsDatapathConnection;


public class AddDatapathCommand extends Command<AddDatapathResult> {

    private String datapathName;

    public AddDatapathCommand(String datapathName) {
        this.datapathName = datapathName;
    }

    public Future<AddDatapathResult> execute(OvsDatapathConnection connection) {
        return run(new AddDatapathCallable(connection, datapathName));
    }

}
