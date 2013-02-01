/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands;

import java.util.concurrent.Future;

import org.midonet.mmdpctl.commands.callables.DumpDatapathCallable;
import org.midonet.mmdpctl.commands.results.DumpDatapathResult;
import org.midonet.odp.protos.OvsDatapathConnection;


public class DumpDatapathCommand extends Command<DumpDatapathResult>{

    String datapath;

    public DumpDatapathCommand(String datapath) {
        this.datapath = datapath;
    }

    public Future<DumpDatapathResult> execute(OvsDatapathConnection connection) {
        return run(new DumpDatapathCallable(connection, datapath));
    }
}
