/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

public abstract class CommandExecutor<T> {

    String targetName;
    T param;
    Class<T> clazz;

    protected CommandExecutor(Class<T> clazz) {
        this.clazz = clazz;
    }

    public abstract void execute();

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

    public void setParam(T param) {
        this.param = param;
    }
}
