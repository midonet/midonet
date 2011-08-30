/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

/**
 * A builder of Open vSwitch queues.
 *
 * The set of parameters that can be set on a queue depends on the type of the
 * queue's QoS type ("min-rate", "linux-htb", or "linux-hfsc").
 */
public interface QueueBuilder {

    /**
     * Clear all the optional parameters of this queue.
     * @return this builder
     */
    QueueBuilder clear();

    /**
     * Add an arbitrary pair of key-value strings to associate with the queue.
     *
     * This method can be called several times to associate several external
     * IDs with the queue.
     *
     * @param key the external ID key
     * @param value the external ID
     * @return this builder
     */
    QueueBuilder externalId(String key, String value);

    /**
     * Set the minimum guaranteed bandwidth, in bit/s.
     *
     * If this method is not called, no minimum rate is guaranteed.
     *
     * @param minRate minimum guaranteed bandwidth, in bit/s
     * @return this builder
     */
    QueueBuilder minRate(long minRate);

    /**
     * Set the maximum allowed bandwidth, in bit/s. The queue's rate will not
     * be allowed to exceed the specified value, even if excess bandwidth is
     * available.
     *
     * If this method is not called, no limit is enforced.
     *
     * This parameter is significant only for QoSes of type "linux-htb" or
     * "linux-hfsc".
     *
     * @param maxRate maximum allowed bandwidth, in bit/s
     * @return this builder
     */
    QueueBuilder maxRate(long maxRate);

    /**
     * Set the burst size, in bits. This is the maximum amount of "credits"
     * that a queue can accumulate while it is idle. Details of the linux-htb
     * implementation require a minimum burst size, so a too-small burst will
     * be silently ignored.
     *
     * If this method is not called, the burst is implementation-specific.
     *
     * This parameter is significant only for QoSes of type "linux-htb".
     *
     * @param burst burst size, in bits
     * @return this builder
     */
    QueueBuilder burst(long burst);

    /**
     * Set the priority. A queue with a smaller priority will receive all the
     * excess bandwidth that it can use before a queue with a larger value
     * receives any. Specific priority values are unimportant; only relative
     * ordering matters. The priority must be a nonnegative 32-bit integer.
     *
     * If this method is not called, the priority is set to 0.
     *
     * This parameter is significant only for QoSes of type "linux-htb".
     *
     * @param priority nonnegative 32-bit integer priority
     * @return this builder
     */
    QueueBuilder priority(long priority);

    /**
     * Build and add or update the queue.
     *
     * @return the added or updated queue's UUID
     */
    String build();

    /**
     * Update the queue.
     *
     * @param queueUuid The UUID of the queue to update.
     * @return this builder
     */
    QueueBuilder update(String queueUuid);

}
