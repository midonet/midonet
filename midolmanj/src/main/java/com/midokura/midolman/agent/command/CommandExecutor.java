/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import com.midokura.midolman.agent.state.HostDirectory;

public abstract class CommandExecutor<T> {

    public static class CommandExecutionFailedException extends Exception {
        public CommandExecutionFailedException(String s) {
            super(s);
        }
    }

    String targetName;
    T param;
    Class<T> clazz;
    HostDirectory.Command.AtomicCommand.OperationType operationType;

    protected CommandExecutor(Class<T> clazz) {
        this.clazz = clazz;
    }

    public abstract void execute() throws CommandExecutionFailedException;

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public T getParam() {
        return param;
    }

    public void setParamAsObject(Object param) {
        setParam(clazz.cast(param));
    }

    protected void setParam(T param) {
        this.param = param;
    }

    public HostDirectory.Command.AtomicCommand.OperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(HostDirectory.Command.AtomicCommand.OperationType operationType) {
        this.operationType = operationType;
    }
}
