/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.host.commands.executors;

import org.midonet.midolman.util.Sudo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AddressCommandExecutor extends AbstractCommandExecutor<String> {

    private final static Logger log =
            LoggerFactory.getLogger(AddressCommandExecutor.class);

    protected AddressCommandExecutor() {
        super(String.class);
    }

    @Override
    public void execute() {
        try {
            int returnValue = Sudo.sudoExec("ip addr add " + param + " dev " + targetName);

            // if there was an error, log it
            if (returnValue != 0) {
                log.warn ("Cannot add IP address " + param + " to device " + targetName + " (" + returnValue + ")");
            }
        } catch (Exception e) {
            log.warn("Cannot add IP address " + param + " to device " + targetName, e);
        }
    }
}
