/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.util.Sudo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InterfaceCommandExecutor extends CommandExecutor<String> {

    private final static Logger log =
            LoggerFactory.getLogger(InterfaceCommandExecutor.class);

    protected InterfaceCommandExecutor() {
        super(String.class);
    }

    private enum InterfaceType {
        TUN, TAP, UNKNOWN;

        @Override
        // this is override in order to be used in the command line
        public String toString() {
            return name().toLowerCase();
        }
    }

    private enum InterfaceAction {
        ADD, DELETE, UNKNOWN;

        @Override
        // this is override in order to be used in the command line
        public String toString() {
            if (this.equals(DELETE)) {
                return "del";
            }
            return name().toLowerCase();
        }
    }

    @Override
    public void execute() {

        // Get interface mode from param
        InterfaceType interfaceType = InterfaceType.UNKNOWN;
        if (param.equals("tun")) {
            interfaceType = InterfaceType.TUN;
        } else if (param.equals("tap")) {
            interfaceType = InterfaceType.TAP;
        }

        // We don't support an unknown interface type
        if (interfaceType.equals(InterfaceType.UNKNOWN)) {
            log.warn("Unsupported interface type");
            return;
        }

        // Get the interface action (add/del)
        InterfaceAction interfaceAction = InterfaceAction.UNKNOWN;
        if (operationType.equals(HostDirectory.Command.AtomicCommand.OperationType.SET)) {
            interfaceAction = InterfaceAction.ADD;
        } else if (operationType.equals(HostDirectory.Command.AtomicCommand.OperationType.DELETE)) {
            interfaceAction = InterfaceAction.DELETE;
        }

        // We don't support unknown operations
        if (interfaceAction.equals(InterfaceAction.UNKNOWN)) {
            log.warn("Unsupported interface action");
            return;
        }

        executeAction(interfaceType, interfaceAction);
    }

    private void executeAction(InterfaceType interfaceType, InterfaceAction interfaceAction) {
        try {
            int returnValue = Sudo.sudoExec("ip tuntap " + interfaceAction + " dev " +
                    targetName + " mode " + interfaceType );
            // if there was an error, log it
            if (returnValue != 0) {
                log.warn("Cannot " + interfaceAction + " interface " + targetName + " (" + returnValue + ")");
            }
        } catch (Exception e) {
            log.warn ("Cannot " + interfaceAction + " interface " + targetName, e);
        }
    }
}
