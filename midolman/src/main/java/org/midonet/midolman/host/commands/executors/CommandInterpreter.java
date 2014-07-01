/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.host.commands.executors;

import java.util.ArrayList;
import java.util.List;
import static java.lang.String.format;

import com.google.inject.Inject;
import com.google.inject.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.midolman.host.state.HostDirectory.Command;

public class CommandInterpreter {

    @Inject
    Injector injector;

    public static class InvalidParameterException extends Exception {

        private static final long serialVersionUID = 1L;

        public InvalidParameterException(CommandProperty property,
                                         String actualValue,
                                         String message) {
            super(
                String.format(
                    "Couldn't accept property: %s with value=%s to type: %s. Error message: %s",
                    property, actualValue,
                    property.getType().getCanonicalName(), message));
        }
    }

    public static class InvalidExecutorInstanceException extends Exception {

        private static final long serialVersionUID = 1L;

        public InvalidExecutorInstanceException(CommandProperty property,
                                                Exception e) {
            super(
                format("Cannot instantiate the CommandExecutor for " +
                           "property: %s (we wanted: %s)",
                       property, property.getExecutor().getCanonicalName()), e);
        }
    }

    private final static Logger log =
        LoggerFactory.getLogger(CommandInterpreter.class);


    public List<CommandExecutor<?>> interpret(Command cmd)
        throws InvalidParameterException, InvalidExecutorInstanceException {
        log.debug("Interpreting command: {}.", cmd);

        List<CommandExecutor<?>> executors = new ArrayList<>();
        List<Command.AtomicCommand> cmdList = cmd.getCommandList();
        for (Command.AtomicCommand atomicCmd : cmdList) {
            CommandProperty property = atomicCmd.getProperty();
            String value = atomicCmd.getValue();
            Object parameter = null;

            if (value != null && !value.trim().equals("")) {
                try {
                    parameter = property.getValidator()
                                        .validateAndConvert(property, value);
                } catch (Exception e) {
                    throw new InvalidParameterException(property, value,
                                                        e.getMessage());
                }
            }

            CommandExecutor<?> executor;
            try {
                executor = property.getExecutor().newInstance();
                injector.injectMembers(executor);
            } catch (Exception ex) {
                throw new InvalidExecutorInstanceException(property, ex);
            }

            executor.setTargetName(cmd.getInterfaceName());
            executor.setParamAsObject(parameter);
            executor.setOperationType(atomicCmd.getOpType());

            executors.add(executor);
        }

        return executors;
    }
}
