/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

/**
 * A builder of Open vSwitch controller connection.
 */
public interface ControllerBuilder {

    /**
     * Add an arbitrary pair of key-value strings to associate with the
     * controller.
     *
     * This method can be called several times to associate several external
     * IDs with the controller.
     *
     * @param key the external ID key
     * @param value the external ID
     * @return this builder
     */
    ControllerBuilder externalId(String key, String value);

    /**
     * Specify how the controller is contacted over the network.
     *
     * If this method is not called, the connection mode is
     * implementation-specific.
     *
     * @param connectionMode specifies how the controller is contacted over
     * the network
     * @return this builder
     */
    ControllerBuilder connectionMode(
            ControllerConnectionMode connectionMode);

    /**
     * Set the maximum number of milliseconds to wait between connection
     * attempts.
     *
     * If this method is not called, the number is implementation-specific.
     *
     * @param maxBackoff maximum number of milliseconds to wait between
     * connection attempts
     * @return this builder
     */
    ControllerBuilder maxBackoff(int maxBackoff);

    /**
     * Set the maximum number of milliseconds of idle time on connection to
     * controller before sending an inactivity probe message.
     *
     * If this method is not called, the number is implementation-specific.
     *
     * @param inactivityProbe maximum number of milliseconds of idle time on
     * connection to controller before sending an inactivity probe message
     * @return this builder
     */
    ControllerBuilder inactivityProbe(int inactivityProbe);

    /**
     * Set the maximum rate at which packets in unknown flows will be forwarded
     * to the OpenFlow controller, in packets per second.
     *
     * If this method is not called, the rate is implementation-specific.
     *
     * @param controllerRateLimit maximum rate at which packets in unknown
     * flows will be forwarded to the OpenFlow controller, in packets per
     * second
     * @return this builder
     */
    ControllerBuilder controllerRateLimit(int controllerRateLimit);

    /**
     * Set the maximum number of unused packet credits that the bridge will
     * allow to accumulate, in packets, in conjunction with
     * controllerRateLimit.
     *
     * If this method is not called, the number is implementation-specific.
     *
     * @param controllerBurstLimit maximum number of unused packet credits
     * that the bridge will allow to accumulate, in packets
     * @return this builder
     */
    ControllerBuilder controllerBurstLimit(int controllerBurstLimit);

    /**
     * Set a POSIX extended regular expression against which the discovered
     * controller location is validated, if the controller target is
     * "discover". If the target is not "discover", this parameter is ignored.
     *
     * If this method is not called, the regexp is implementation-specific.
     *
     * @param discoverAcceptRegex a POSIX extended regular expression
     * against which the discovered controller location is validated
     * @return this builder
     */
    ControllerBuilder discoverAcceptRegex(String discoverAcceptRegex);

    /**
     * Set whether to update /etc/resolv.conf when the controller is
     * discovered, if the target is "discover". If the target is not
     * "discover", this parameter is ignored.
     *
     * If this method is not called, this setting is implementation-specific.
     *
     * @param discoverUpdateResolvConf specifies whether to update
     * /etc/resolv.conf when the controller is discovered
     * @return this builder
     */
    ControllerBuilder discoverUpdateResolvConf(
            boolean discoverUpdateResolvConf);

    /**
     * Set the IP address to configure on the local port to connect to the
     * controller. This can be set only when the connectionMode is 'in-band'
     * and the target is 'discover'.
     *
     * If this method is not called, DHCP will be used to autoconfigure the
     * local port.
     *
     * @param localIp the IP address to configure on the local port to connect
     * to the controller
     * @return this builder
     */
    ControllerBuilder localIp(String localIp);

    /**
     * Set the IP netmask to configure on the local port. If localIp is not
     * set, this parameter is ignored.
     *
     * If this method is not called, the IP netmask is automatically
     * determined from the localIp.
     *
     * @param localNetmask the IP netmask to configure on the local port
     * @return this builder
     */
    ControllerBuilder localNetmask(String localNetmask);

    /**
     * Set the IP address of the gateway to configure on the local port. If
     * localIp is not set, this parameter is ignored.
     *
     * If this method is not called, no gateway will be used to connect to the
     * controller.
     *
     * @param localGateway the IP address of the gateway to configure on the
     * local port
     * @return this builder
     */
    ControllerBuilder localGateway(String localGateway);

    /**
     * Build and add the controller.
     */
    void build();

}
