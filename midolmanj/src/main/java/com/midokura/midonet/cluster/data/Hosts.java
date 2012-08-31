/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data;

import java.util.HashSet;
import java.util.UUID;

import com.midokura.midolman.host.state.HostDirectory;

/**
 *
 */
public class Hosts {
    public static HostDirectory.Metadata toHostConfig(Host host) {
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();

        metadata.setName(host.getName());
        metadata.setAddresses(host.getAddresses());
        metadata.setAvailabilityZones(new HashSet<UUID>(host.getAvailabilityZones()));

        return metadata;
    }
}
