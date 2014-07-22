/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.config;

/**
 * This interface is used by host configuration that requires unique local
 * identifiers.
 */
public interface HostIdConfig {

    /**
     * Gets a unique identifier for this host.
     * @return The identifier.
     */
    String getHostId();

    /**
     * Gets the path of the host properties file.
     * @return The file path.
     */
    String getHostPropertiesFilePath();
}
