/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

/**
 * A builder of Open vSwitch ports.
 */
public interface PortBuilder {

    /**
     * Add an arbitrary pair of key-value strings to associate with the port.
     *
     * This method can be called several times to associate several external
     * IDs with the port.
     *
     * @param key the external ID key
     * @param value the external ID
     * @return this builder
     */
    PortBuilder externalId(String key, String value);

    /**
     * Set the MAC address of the underlying interface.
     *
     * If this method is not called, a MAC address is determined by the bridge.
     *
     * @param ifMac the MAC address of the underlying interface
     * @return this builder
     */
    PortBuilder ifMac(byte[] ifMac);

    /**
     * Build and add the port.
     */
    void build();

}
