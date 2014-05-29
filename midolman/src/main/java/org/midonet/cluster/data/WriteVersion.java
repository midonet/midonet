/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import javax.annotation.Nonnull;

public class WriteVersion {

    Data data;

    public WriteVersion() {
        this(new Data());
    }
    public WriteVersion(@Nonnull Data data) {
        this.data = data;
    }

    protected WriteVersion self() {
        return this;
    }

    public Data getData() {
        return data;
    }

    public WriteVersion setData(Data data) {
        this.data = data;
        return self();
    }

    public WriteVersion setVersion(String version) {
        getData().version = version;
        return self();
    }

    public String getVersion() {
        return getData().version;
    }

    public static class Data {
        public String version;
    }
}
