/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.host.commands.executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.process.ProcessHelper;

public class MtuCommandExecutor extends AbstractCommandExecutor<Integer> {

    private final static Logger log =
            LoggerFactory.getLogger(MtuCommandExecutor.class);

    protected MtuCommandExecutor() {
        super(Integer.class);
    }

    @Override
    public void execute() throws CommandExecutionFailedException {
        int returnValue;
        try {
            returnValue =
                ProcessHelper
                    .newProcess("ifconfig " + targetName + " mtu " + param)
                    .withSudo()
                    .runAndWait();
        } catch (Exception e) {
            throw new CommandExecutionFailedException("Cannot set MTU for " +
                                                   targetName + e.getMessage());
        }
        // if there was an error, throw a CommandExecutionFailedException
        if (returnValue != 0) {
            throw new CommandExecutionFailedException("Cannot set MTU for " +
                                         targetName + " (" + returnValue + ")");
        }
    }
}
