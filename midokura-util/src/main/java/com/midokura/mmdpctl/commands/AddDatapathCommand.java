package com.midokura.mmdpctl.commands;

import com.midokura.mmdpctl.commands.callables.AddDatapathCallable;
import com.midokura.mmdpctl.commands.results.AddDatapathResult;

import java.util.concurrent.Future;

public class AddDatapathCommand extends Command<AddDatapathResult> {

    private String datapathName;

    public AddDatapathCommand(String datapathName) {
        this.datapathName = datapathName;
    }

    public Future<AddDatapathResult> execute() {
        return run(new AddDatapathCallable(datapathName));
    }

}
