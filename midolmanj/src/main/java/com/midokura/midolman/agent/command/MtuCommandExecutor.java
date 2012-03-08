/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MtuCommandExecutor extends CommandExecutor<Integer> {

    private final static Logger log =
            LoggerFactory.getLogger(MtuCommandExecutor.class);

    protected MtuCommandExecutor() {
        super(Integer.class);
    }

    @Override
    public void execute() {
        //TODO(rossella) it's a stub
        log.info("Modifying MTU of " + targetName + " to " + param.toString());
    }
}
