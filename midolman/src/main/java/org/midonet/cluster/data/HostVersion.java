/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import javax.annotation.Nonnull;
import java.util.UUID;

public class HostVersion {
    Data data;

    public HostVersion() {
        this(new Data());
    }

    public HostVersion(@Nonnull Data data) {
        this.data = data;
    }

    protected HostVersion self() {
        return this;
    }

    public HostVersion setData(Data data) {
        this.data = data;
        return self();
    }

    public Data getData() {
        return this.data;
    }

    public HostVersion setVersion(String version) {
        getData().version = version;
        return self();
    }

    public String getVersion() {
        return getData().version;
    }

    public HostVersion setHostId(UUID id) {
        getData().hostId = id;
        return self();
    }

    public UUID getHostId() {
        return getData().hostId;
    }

    public static class Data {
        public String version;
        public UUID hostId;
    }
}
