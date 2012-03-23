/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.commands.executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.state.HostDirectory;

public abstract class AbstractCommandExecutor<T> implements CommandExecutor<T> {

    private final static Logger log =
        LoggerFactory.getLogger(AbstractCommandExecutor.class);

    String targetName;
    T param;
    Class<T> clazz;
    HostDirectory.Command.AtomicCommand.OperationType operationType;

    protected AbstractCommandExecutor(Class<T> clazz) {
        this.clazz = clazz;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public T getParam() {
        return param;
    }

    public void setParam(T param) {
        this.param = param;
    }

    public void setParamAsObject(Object param) {
        try {
            setParam(clazz.cast(param));
        } catch (RuntimeException e) {
            log.error("Exception while casting an object of type {} to type {}",
                      param.getClass().getCanonicalName(), clazz.getCanonicalName());
            throw e;
        }
    }

    public HostDirectory.Command.AtomicCommand.OperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(HostDirectory.Command.AtomicCommand.OperationType operationType) {
        this.operationType = operationType;
    }
}
