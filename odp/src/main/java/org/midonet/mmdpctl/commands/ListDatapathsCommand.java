/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.mmdpctl.commands;

import java.util.concurrent.Future;

import org.midonet.mmdpctl.commands.callables.ListDatapathsCallable;
import org.midonet.mmdpctl.commands.results.ListDatapathsResult;
import org.midonet.odp.protos.OvsDatapathConnection;


public class ListDatapathsCommand extends Command<ListDatapathsResult>{

    public Future<ListDatapathsResult> execute(OvsDatapathConnection connection) {
        return run(new ListDatapathsCallable(connection));
    }
}
