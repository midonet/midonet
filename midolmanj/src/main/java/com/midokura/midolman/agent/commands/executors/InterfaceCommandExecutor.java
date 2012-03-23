/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.commands.executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.util.Sudo;

public class InterfaceCommandExecutor
    extends AbstractCommandExecutor<HostDirectory.Interface.Type> {

    private final static Logger log =
        LoggerFactory.getLogger(InterfaceCommandExecutor.class);

    protected InterfaceCommandExecutor() {
        super(HostDirectory.Interface.Type.class);
    }

    public enum InterfaceType {
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
        InterfaceType interfaceType;

        switch (getParam()) {
            case Virtual:
                interfaceType = InterfaceType.TAP;
                break;
            default:
                interfaceType = InterfaceType.UNKNOWN;
                break;
        }

        // We don't support an unknown interface type
        if (interfaceType == InterfaceType.UNKNOWN) {
            log.warn("Unsupported interface type {} (derived from parameter value: {})",
                     interfaceType, getParam());
            return;
        }

        // Get the interface action (add/del)
        InterfaceAction interfaceAction = null;
        switch (getOperationType()) {
            case SET:
                interfaceAction = InterfaceAction.ADD;
                break;
            case DELETE:
                interfaceAction = InterfaceAction.DELETE;
                break;
        }

        // We don't support unknown operations
        if (interfaceAction == null) {
            log.warn("Unsupported interface action: {} ", interfaceAction);
            return;
        }

        executeAction(interfaceType, interfaceAction);
    }

    private void executeAction(InterfaceType interfaceType,
                               InterfaceAction interfaceAction) {
        try {
            int returnValue =
                Sudo.sudoExec(
                    String.format("ip tuntap %s dev %s mode %s",
                                  interfaceAction, targetName, interfaceType));

            // if there was an error, log it
            if (returnValue != 0) {
                log.warn("Cannot {}  interface {} ({})",
                         new Object[]{interfaceAction, targetName, returnValue});
            }
        } catch (Exception e) {
            log.warn("Cannot " + interfaceAction + " interface " + targetName,
                     e);
        }
    }
}
