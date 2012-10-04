package com.midokura.mmdpctl.commands;

import com.midokura.mmdpctl.commands.callables.ListDatapathsCallable;
import com.midokura.mmdpctl.commands.results.ListDatapathsResult;

import java.util.concurrent.Future;


public class ListDatapathsCommand extends Command<ListDatapathsResult>{

    public Future<ListDatapathsResult> execute() {
        return run(new ListDatapathsCallable());
    }
}
