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
    public void execute() {
        try {
            int returnValue = Sudo.sudoExec("ifconfig " + targetName + " hw ether " + param);
            // if there was an error, log it
            if (returnValue != 0) {
                log.warn("Cannot set MAC address for " + targetName + " (" + returnValue + ")");
            }
        } catch (Exception e) {
            log.warn("Cannot set MAC address for " + targetName, e);
        }
    }
}
