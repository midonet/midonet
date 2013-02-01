/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.host;

import org.midonet.cluster.data.Entity;

import java.util.Calendar;
import java.util.Date;

/**
 * Host error log item
 */
public class ErrorLogItem extends Entity.Base<Integer, ErrorLogItem.Data,
        ErrorLogItem> {

    public ErrorLogItem() {
        this(null, new Data());
    }

    public ErrorLogItem(Integer id, Data data) {
        super(id, data);
    }

    @Override
    protected ErrorLogItem self() {
        return this;
    }

    public Integer getCommandId() {
        return getData().commandId;
    }

    public ErrorLogItem setCommandId(Integer commandId) {
        getData().commandId = commandId;
        return self();
    }

    public String getError() {
        return getData().error;
    }

    public ErrorLogItem setError(String error) {
        getData().error = error;
        return self();
    }

    public String getInterfaceName() {
        return getData().interfaceName;
    }

    public ErrorLogItem setInterfaceName(String interfaceName) {
        getData().interfaceName = interfaceName;
        return self();
    }

    public Date getTime() {
        return getData().time;
    }

    public ErrorLogItem setTime(Date time) {
        getData().time = time;
        return self();
    }

    public static class Data {

        public Integer commandId;
        public String error;
        public String interfaceName;
        public Date time = Calendar.getInstance().getTime();
    }
}
