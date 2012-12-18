/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands;

import java.util.concurrent.Future;

import com.midokura.mmdpctl.commands.callables.DumpDatapathCallable;
import com.midokura.mmdpctl.commands.results.DumpDatapathResult;
import com.midokura.odp.protos.OvsDatapathConnection;


public class DumpDatapathCommand extends Command<DumpDatapathResult>{

    String datapath;

    public DumpDatapathCommand(String datapath) {
        this.datapath = datapath;
    }

    public Future<DumpDatapathResult> execute(OvsDatapathConnection connection) {
        return run(new DumpDatapathCallable(connection, datapath));
    }
}
