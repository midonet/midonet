/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.mmdpctl.commands;

import com.midokura.mmdpctl.commands.callables.ListDatapathsCallable;
import com.midokura.mmdpctl.commands.results.ListDatapathsResult;
import com.midokura.netlink.protos.OvsDatapathConnection;

import java.util.concurrent.Future;

public class ListDatapathsCommand extends Command<ListDatapathsResult>{

    public Future<ListDatapathsResult> execute(OvsDatapathConnection connection) {
        return run(new ListDatapathsCallable(connection));
    }
}
