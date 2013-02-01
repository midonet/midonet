/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import java.util.UUID;

public class PortSet extends Entity.Base<UUID, PortSet.Data, PortSet>{

    public PortSet(UUID uuid) {
        super(uuid, new Data());
    }

    @Override
    protected PortSet self() {
        return this;
    }

    public static class Data {}

}
