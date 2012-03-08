/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import java.util.ArrayList;
import java.util.List;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.state.HostDirectory;

public class CommandInterpreter {

    public class InvalidParameterException extends Exception {
        public InvalidParameterException(String property, String actualValue,
                                         String acceptedValue) {
            super("Couldn't accept property: " + property + " it's " + actualValue
                          + "should be " + acceptedValue);
        }
    }

    public class InvalidExecutorInstanceException extends Exception {
        public InvalidExecutorInstanceException(String property, String executorClass) {
            super("Cannot instanciate the CommandExecutor " + executorClass
                          + ".for property " + property);
        }
    }

    private final static Logger log =
            LoggerFactory.getLogger(CommandInterpreter.class);


    public CommandExecutor[] interpret(HostDirectory.Command cmd)
            throws InvalidParameterException, InvalidExecutorInstanceException {
        List<CommandExecutor> cmdToExecute = new ArrayList<CommandExecutor>();
        List<HostDirectory.Command.AtomicCommand> cmdList = cmd.getCommandList();
        for (HostDirectory.Command.AtomicCommand atomicCmd : cmdList) {
            String property = atomicCmd.getProperty();
            String value = atomicCmd.getValue();
            Object convertedValue;
            try {
                convertedValue = CommandValidatorFactory.getValidator(property)
                                           .validateAndConvert(property, value);
            } catch (Exception e) {
                throw new InvalidParameterException(property, value,
                           PropertyExecutor.get(property).getType().toString());
            }
            CommandExecutor executor = null;
            try {
                executor = PropertyExecutor.get(property).getExecutor().newInstance();
                executor.setTargetName(cmd.getInterfaceName());
                executor.setParamAsObject(convertedValue);
                executor.setOperationType(atomicCmd.getOpType());
            } catch (Exception e) {
                throw new InvalidExecutorInstanceException(property,
                    PropertyExecutor.get(property).getExecutor().getClass().toString());
            }
            cmdToExecute.add(executor);
        }
        return cmdToExecute.toArray(new CommandExecutor[cmdToExecute.size()]);
    }
}
