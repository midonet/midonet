/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

/**
 * A builder of Open vSwitch bridges.
 */
public interface BridgeBuilder {

    /**
     * Add an arbitrary pair of key-value strings to associate with the bridge.
     *
     * This method can be called several times to associate several external
     * IDs with the bridge.
     *
     * @param key the external ID key
     * @param value the external ID
     * @return this builder
     */
    BridgeBuilder externalId(String key, String value);
    
    /**
     * Add an arbitrary pair of key-value strings to associate with the bridge.
     *
     * This method can be called several times to associate several external
     * IDs with the bridge.
     *
     * @param key the external ID key
     * @param value the external ID
     * @return this builder
     */
    BridgeBuilder otherConfig(String key, String value);

    /**
     * Set the switch's mode.
     *
     * @param failMode the mode of the switch when set to connect to one or
     * more controllers and none of of the controllers can be contacted
     * @return this builder
     */
    BridgeBuilder failMode(BridgeFailMode failMode);

    /**
     * Build and add the bridge.
     */
    void build();

}
