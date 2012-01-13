/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

import java.util.Map;

/**
 * A builder of Open vSwitch QoSes.
 */
public interface QosBuilder {

    /**
     * Clear all the optional parameters of this QoS.
     * @return this builder
     */
    QosBuilder clear();

    /**
     * Add an arbitrary pair of key-value strings to associate with the QoS.
     *
     * This method can be called several times to associate several external
     * IDs with the QoS.
     *
     * @param key the external ID key
     * @param value the external ID
     * @return this builder
     */
    QosBuilder externalId(String key, String value);

    /**
     * Set the maximum rate shared by all queued traffic in bit/s.
     *
     * If this method is not called, no maximum rate is enforced.
     *
     * @param maxRate the maximum rate shared by all queued traffic in bit/s
     * @return this builder
     */
    QosBuilder maxRate(int maxRate);

    /**
     * Set the queues and their IDs. Queue ID 0 is reserved and must not be
     * used.
     *
     * @param queues a map of queue IDs (positive 32-bit integers) to queue
     * UUIDs
     * @return this builder
     */
    QosBuilder queues(Map<Long, String> queues);

    /**
     * Build and add or update the QoS.
     *
     * @return the added or updated QoS's UUID
     */
    String build();

    /**
     * Update the QoS.
     * used.
     *
     * @param qosUuid the uuid to update.
     * @return this builder
     */
    QosBuilder update(String qosUuid);

}
