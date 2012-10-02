/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

/**
 * A builder of Open vSwitch GRE tunnel ports.
 */
public interface TunnelPortBuilder {

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
    TunnelPortBuilder externalId(String key, String value);

    /**
     * Set the MAC address of the underlying interface.
     *
     * If this method is not called, a MAC address is determined by the bridge.
     *
     * @param ifMac the MAC address of the underlying interface
     * @return this builder
     */
    TunnelPortBuilder ifMac(String ifMac);

    /**
     * Set the tunnel local endpoint's IP address.
     *
     * If this method is not called, any IP address of the local host can be
     * used.
     *
     * @param localIp the tunnel local endpoint's IP address
     * @return this builder
     */
    TunnelPortBuilder localIp(String localIp);

    /**
     * Set the GRE key to be set on outgoing packets.
     *
     * If this method or outKeyFlow() are not called, no GRE key is to be
     * set.
     *
     * @param outKey the GRE key to be set on outgoing packets
     * @return this builder
     */
    TunnelPortBuilder outKey(int outKey);

    /**
     * Don't set the GRE key to be set on outgoing packets. The GRE key may be
     * set using the set_tunnel Nicira OpenFlow vendor extension.
     *
     * If this method or outKey() are not called, no GRE key is to be set.
     *
     * @return this builder
     */
    TunnelPortBuilder outKeyFlow();

    /**
     * Set the GRE key that received packets must contain.
     *
     * If this method or inKeyFlow() are not called, of if inKey is 0, no key
     * is required in received packets.
     *
     * @param inKey the GRE key that received packets must contain, or 0
     * @return this builder
     */
    TunnelPortBuilder inKey(int inKey);

    /**
     * Don't set a GRE key that received packets must contain. Any key will
     * be accepted and the key will be placed in the tun_id field for matching
     * in the flow table.
     *
     * If this method or inKey() are not called, of if inKey() is
     * called with key 0, no key is required in received packets.
     *
     * @return this builder
     */
    TunnelPortBuilder inKeyFlow();

    /**
     * Override outKey and inKey at the same time. Equivalent to calling
     * outKey(key) and inKey(key).
     *
     * @param key the outKey and inKey
     * @return this builder
     */
    TunnelPortBuilder key(int key);

    /**
     * Override outKey and inKey at the same time. Equivalent to calling
     * outKeyFlow() and inKeyFlow().
     *
     * @return this builder
     */
    TunnelPortBuilder keyFlow();

    /**
     * Set the value of the ToS bits to be set on the encapsulating packet.
     *
     * @param tos the value of the ToS bits to be set on the encapsulating
     * packet
     * @return this builder
     */
    TunnelPortBuilder tos(byte tos);

    /**
     * Set the value of the ToS bits to be copied from the inner packet if it
     * is IPv4 or IPv6.
     *
     * @return this builder
     */
    TunnelPortBuilder tosInherit();

    /**
     * Set the TTL to be set on the encapsulating packet.
     *
     * If this method or ttlInherit() are not called, the system's default TTL
     * is used.
     *
     * @param ttl the TTL to be set on the encapsulating packet
     * @return this builder
     */
    TunnelPortBuilder ttl(byte ttl);

    /**
     * Set the TTL to be copied from the inner packet if it is IPv4 or IPv6.
     *
     * If this method or ttl() are not called, the system's default TTL
     * is used.
     *
     * @return this builder
     */
    TunnelPortBuilder ttlInherit();

    /**
     * Enable computing GRE checksums on outgoing packets.
     *
     * If this method is not called, no checksums are computed.
     *
     * @return this builder
     */
    TunnelPortBuilder enableCsum();

    /**
     * Disable tunnel path MTU discovery.
     *
     * If this method is not called, the tunnel path MTU discovery is enabled.
     *
     * @return this builder
     */
    TunnelPortBuilder disablePmtud();

    /**
     * Disable caching of tunnel headers and the output path.
     *
     * If this method is not called, the tunnel headers and output path are
     * cached.
     *
     * @return this builder
     */
    TunnelPortBuilder disableHeaderCache();

    /**
     * Build and add the port.
     */
    void build();

}
