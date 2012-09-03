/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.cluster.data.host;

import com.midokura.midolman.host.state.HostDirectory.Command.AtomicCommand;
import com.midokura.midonet.cluster.data.Entity;

import java.util.*;

/**
 * Host command
 */
public class Command extends Entity.Base<Integer, Command.Data, Command> {

    private ErrorLogItem errorLogItem;

    public Command() {
        this(null, new Data());
    }

    public Command(Integer id, Data data) {
        super(id, data);
    }

    @Override
    protected Command self() {
        return this;
    }

    public String getInterfaceName() {
        return getData().interfaceName;
    }

    public Command setInterfaceName(String interfaceName) {
        getData().interfaceName = interfaceName;
        return self();
    }

    public List<AtomicCommand> getCommandList() {
        return getData().commandList;
    }

    public Command setCommandList(List<AtomicCommand> commandList) {
        getData().commandList = commandList;
        return self();
    }

    public ErrorLogItem getErrorLogItem() {
        return this.errorLogItem;
    }

    public Command setErrorLogItem(ErrorLogItem errorLogItem) {
        this.errorLogItem = errorLogItem;
        return self();
    }

    public static class Data {

        public String interfaceName;

        public List<AtomicCommand> commandList =
                new ArrayList<AtomicCommand>();

        @Override
        public String toString() {
            return "Command{" +
                    "interfaceName='" + interfaceName + '\'' +
                    ", commandList=" + commandList +
                    '}';
        }
    }
}