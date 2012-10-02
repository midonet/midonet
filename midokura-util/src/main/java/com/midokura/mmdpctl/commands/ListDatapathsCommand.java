package com.midokura.mmdpctl.commands;

import com.midokura.mmdpctl.results.ListDatapathsResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Created with IntelliJ IDEA.
 * User: marc
 * Date: 01/10/2012
 * Time: 13:41
 * To change this template use File | Settings | File Templates.
 */
public class ListDatapathsCommand extends Command<ListDatapathsResult>{

    public Future<ListDatapathsResult> execute() {
        return run(new ListDatapathsCallable());
    }
}
