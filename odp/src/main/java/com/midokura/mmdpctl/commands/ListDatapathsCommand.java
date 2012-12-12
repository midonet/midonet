/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands;

import java.util.concurrent.Future;

import com.midokura.mmdpctl.commands.callables.ListDatapathsCallable;
import com.midokura.mmdpctl.commands.results.ListDatapathsResult;
import com.midokura.odp.protos.OvsDatapathConnection;


public class ListDatapathsCommand extends Command<ListDatapathsResult>{

    public Future<ListDatapathsResult> execute(OvsDatapathConnection connection) {
        return run(new ListDatapathsCallable(connection));
    }
}
