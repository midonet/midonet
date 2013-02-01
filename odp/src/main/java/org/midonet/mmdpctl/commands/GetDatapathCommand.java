/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands;

import java.util.concurrent.Future;

import org.midonet.mmdpctl.commands.callables.GetDatapathCallable;
import org.midonet.mmdpctl.commands.results.GetDatapathResult;
import org.midonet.odp.protos.OvsDatapathConnection;


public class GetDatapathCommand extends Command<GetDatapathResult> {

    private String datapathName;

    public GetDatapathCommand(String datapathName) {
        this.datapathName = datapathName;
    }

    public Future<GetDatapathResult> execute(OvsDatapathConnection connection) {
        return run(new GetDatapathCallable(connection, datapathName));
    }

}
