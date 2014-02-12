/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.host.commands.executors;

import java.util.List;
import static java.lang.String.format;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.midolman.util.Sudo.sudoExec;
import static org.midonet.util.process.ProcessHelper.executeCommandLine;

public class MacCommandExecutor extends AbstractCommandExecutor<String> {

    private final static Logger log =
            LoggerFactory.getLogger(MtuCommandExecutor.class);

    protected MacCommandExecutor() {
        super(String.class);
    }

    @Override
    public void execute() throws CommandExecutionFailedException {
        int returnValue;

        try {

            List<String> stdOut =
                executeCommandLine(format("ip link show %s", targetName)).consoleOutput;

            log.debug("Stdout: " + stdOut);
            boolean wasUp =
                !stdOut.isEmpty() && stdOut.get(0).matches(".*state UP.*");

            if (wasUp) {
                sudoExec(format("ip link set %s down", targetName));
            }

            returnValue =
                sudoExec(format("ifconfig %s hw ether %s", targetName, param));

            if (wasUp) {
                sudoExec(format("ip link set %s up", targetName));
            }

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
