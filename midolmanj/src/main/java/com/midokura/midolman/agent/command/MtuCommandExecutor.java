/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import com.midokura.midolman.util.Sudo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MtuCommandExecutor extends CommandExecutor<Integer> {

    private final static Logger log =
            LoggerFactory.getLogger(MtuCommandExecutor.class);

    protected MtuCommandExecutor() {
        super(Integer.class);
    }

    @Override
    public void execute() throws CommandExecutionFailedException {
        int returnValue;
        try {
            returnValue = Sudo.sudoExec(
                    "ifconfig " + targetName + " mtu " + param);
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
