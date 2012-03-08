/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import com.midokura.midolman.util.Sudo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MacCommandExecutor extends CommandExecutor<String> {

    private final static Logger log =
            LoggerFactory.getLogger(MtuCommandExecutor.class);

    protected MacCommandExecutor() {
        super(String.class);
    }

    @Override
    public void execute() throws CommandExecutionFailedException {
        int returnValue;
        try {
            returnValue = Sudo.sudoExec(
                    "ifconfig " + targetName + " hw ether " + param);
        } catch (Exception e) {
            throw new CommandExecutionFailedException(
                    "Cannot set MAC address for " + targetName + e.getMessage());
        }
        // if there was an error, throw a CommandExecutionFailedException
        if (returnValue != 0) {
            throw new CommandExecutionFailedException(
                    "Cannot set MAC address for " + targetName + " (" + returnValue + ")");
        }
    }
}
