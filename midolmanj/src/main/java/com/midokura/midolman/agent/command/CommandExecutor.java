/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.command;

import static com.midokura.midolman.agent.state.HostDirectory.Command.AtomicCommand;

/**
 * Interface expressing the semantics of a local node CommandExecutor.
 * It will get instantiated based on a specific HostCommand entry and called to
 * do it's job when the NodeAgent detects that this should be done.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 3/9/12
 */
public interface CommandExecutor<T> {

    public static class CommandExecutionFailedException extends Exception {
        public CommandExecutionFailedException(String s) {
            super(s);
        }
    }


    public void execute() throws CommandExecutionFailedException;

    public void setTargetName(String targetName);

    public void setParam(T param);

    public void setParamAsObject(Object parameter);

    public void setOperationType(AtomicCommand.OperationType operationType);
}
