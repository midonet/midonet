/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

import java.util.Set;

/**
 * A connection to an Open vSwitch database.
 */
public interface OpenvSwitchDatabaseConnection {

    /**
     * Add a new bridge with the given name.
     *
     * @param name the name of the bridge to add
     * @return a builder to set optional parameters of the bridge and add it
     */
    BridgeBuilder addBridge(String name);

    /**
     * Create a port and a system interface, and add the port to a bridge.
     *
     * A system interface is for instance a physical Ethernet interface.
     *
     * @param bridgeId the datapath identifier of the bridge to add the port
     * to
     * @param portName the name of the port and of the interface to create
     * @return a builder to set optional parameters of the port and add it
     */
    PortBuilder addSystemPort(long bridgeId, String portName);

    /**
     * Create a port and a system interface, and add the port to a bridge.
     *
     * A system interface is for instance a physical Ethernet interface.
     *
     * @param bridgeName the name of the bridge to add the port to
     * @param portName the name of the port and of the interface to create
     * @return a builder to set optional parameters of the port and add it
     */
    PortBuilder addSystemPort(String bridgeName, String portName);

    /**
     * Create a port and an internal interface, and add the port to a bridge
     *
     * An internal interface is a virtual physical Ethernet interface usable
     * to exchange packets only with the bridge.
     *
     * @param bridgeId the datapath identifier of the bridge to add the port
     * to
     * @param portName the name of the port and of the interface to create
     * @return a builder to set optional parameters of the port and add it
     */
    PortBuilder addInternalPort(long bridgeId, String portName);

    /**
     * Create a port and an internal interface, and add the port to a bridge
     *
     * An internal interface is a virtual physical Ethernet interface usable
     * to exchange packets only with the bridge.
     *
     * @param bridgeName the name of the bridge to add the port to
     * @param portName the name of the port and of the interface to create
     * @return a builder to set optional parameters of the port and add it
     */
    PortBuilder addInternalPort(String bridgeName, String portName);

    /**
     * Create a port and a TAP interface, and add the port to a bridge.
     *
     * @param bridgeId the datapath identifier of the bridge to add the port
     * to
     * @param portName the name of the port and of the TAP interface to create
     * @return a builder to set optional parameters of the port and add it
     */
    PortBuilder addTapPort(long bridgeId, String portName);

    /**
     * Create a port and a TAP interface, and add the port to a bridge.
     *
     * @param bridgeName the name of the bridge to add the port to
     * @param portName the name of the port and of the TAP interface to create
     * @return a builder to set optional parameters of the port and add it
     */
    PortBuilder addTapPort(String bridgeName, String portName);

    /**
     * Create a port and a GRE interface, and add the port to a bridge.
     *
     * @param bridgeId the datapath identifier of the bridge to add the port
     * to
     * @param portName the name of the port and of the TAP interface to create
     * @param remoteIp the tunnel remote endpoint's IP address
     * @return a builder to set optional parameters of the port and add it
     */
    GrePortBuilder addGrePort(long bridgeId, String portName, String remoteIp);

    /**
     * Create a port and a GRE interface, and add the port to a bridge.
     *
     * @param bridgeName the name of the bridge to add the port to
     * @param portName the name of the port and of the TAP interface to create
     * @param remoteIp the tunnel remote endpoint's IP address
     * @return a builder to set optional parameters of the port and add it
     */
    GrePortBuilder addGrePort(String bridgeName, String portName,
                              String remoteIp);

    /**
     * Delete the port with the given name.
     *
     * @param portName the port name
     */
    void delPort(String portName);

    boolean hasPort(String portName);

    /**
     * Add an OpenFlow controller for a bridge.
     *
     * An OpenFlow controller target may be in any of the following forms
     * for a primary controller (i.e. a normal OpenFlow controller):
     *     'ssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633)
     *         on the host at the given ip, which must be expressed as an
     *         IP address (not a DNS name).
     *     'tcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633)
     *         on the host at the given ip, which must be expressed as an
     *         IP address (not a DNS name).
     *     'discover': The switch discovers the controller by broadcasting
     *         DHCP requests with vendor class identifier 'OpenFlow'.
     *
     * An OpenFlow controller target may be in any of the following forms
     * for a service controller (i.e. a controller that only connects
     * temporarily and doesn't affect the datapath's failMode):
     *     'pssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633)
     *         and ip Open vSwitch listens on for connections from
     *         controllers; the given ip must be expressed as an IP address
     *         (not a DNS name).
     *     'ptcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633)
     *         and ip Open vSwitch listens on for connections from
     *         controllers; the given ip must be expressed as an IP address
     *         (not a DNS name).
     *
     * @param bridgeId the datapath identifier of the bridge to add the
     * controller to
     * @param target the target to connect to the OpenFlow controller
     * @return a builder to set optional parameters of the controller
     * connection and add it
     */
    ControllerBuilder addBridgeOpenflowController(long bridgeId,
                                                  String target);

    /**
     * Add an OpenFlow controller for a bridge.
     *
     * An OpenFlow controller target may be in any of the following forms
     * for a primary controller (i.e. a normal OpenFlow controller):
     *     'ssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633)
     *         on the host at the given ip, which must be expressed as an
     *         IP address (not a DNS name).
     *     'tcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633)
     *         on the host at the given ip, which must be expressed as an
     *         IP address (not a DNS name).
     *     'discover': The switch discovers the controller by broadcasting
     *         DHCP requests with vendor class identifier 'OpenFlow'.
     *
     * An OpenFlow controller target may be in any of the following forms
     * for a service controller (i.e. a controller that only connects
     * temporarily and doesn't affect the datapath's failMode):
     *     'pssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633)
     *         and ip Open vSwitch listens on for connections from
     *         controllers; the given ip must be expressed as an IP address
     *         (not a DNS name).
     *     'ptcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633)
     *         and ip Open vSwitch listens on for connections from
     *         controllers; the given ip must be expressed as an IP address
     *         (not a DNS name).
     *
     * @param bridgeName the name of the bridge to add the controller to
     * @param target the target to connect to the OpenFlow controller
     * @return a builder to set optional parameters of the controller
     * connection and add it
     */
    ControllerBuilder addBridgeOpenflowController(String bridgeName,
                                                  String target);

    /**
     * Delete all the OpenFlow controller targets for a bridge.
     *
     * @param bridgeId the datapath identifier of the bridge
     */
    void delBridgeOpenflowControllers(long bridgeId);

    /**
     * Delete all the OpenFlow controller targets for a bridge.
     *
     * @param bridgeName the name of the bridge
     */
    void delBridgeOpenflowControllers(String bridgeName);

    /**
     * Determine whether a bridge with a given ID exists.
     *
     * @param bridgeId the datapath identifier of the bridge
     * @return whether a bridge with the given ID exists
     */
    boolean hasBridge(long bridgeId);

    /**
     * Determine whether a bridge with a given name exists.
     *
     * @param bridgeName the name of the bridge
     * @return whether a bridge with the given name exists
     */
    boolean hasBridge(String bridgeName);

    /**
     * Delete the bridge with the given ID.
     *
     * @param bridgeId the datapath identifier of the bridge to delete
     */
    void delBridge(long bridgeId);

    /**
     * Delete the bridge with the given name.
     *
     * @param bridgeName the name of the bridge
     */
    void delBridge(String bridgeName);

    /**
     * Get an external ID associated with a bridge given its ID.
     *
     * @param bridgeId the datapath identifier of the bridge
     * @param externalIdKey the key of the external ID to look up
     * @return the value of the external id, or null if no bridge with that
     * datapath ID exists or if the bridge has no external ID with that key
     */
    String getDatapathExternalId(long bridgeId, String externalIdKey);

    /**
     * Get an external ID associated with a bridge given its name.
     *
     * @param bridgeName the name of the bridge
     * @param externalIdKey the key of the external ID to look up
     * @return the value of the external id, or null if no bridge with that
     * name exists or if the bridge has no external ID with that key
     */
    String getDatapathExternalId(String bridgeName, String externalIdKey);

    /**
     * Get an external ID associated with a port given its name.
     *
     * @param portName the name of the port
     * @param externalIdKey the key of the external ID to look up
     * @return the value of the external ID, or null if no port with that name
     * exists or if the port has no external id with that key
     */
    String getPortExternalId(String portName, String externalIdKey);

    /**
     * Get an external ID associated with a given OpenFlow port number.
     *
     * @param bridgeId the datapath identifier of the bridge that contains the
     * port
     * @param portNum the OpenFlow number of the port
     * @param externalIdKey the key of the external ID to look up
     * @return the value of the external ID, or null if no bridge with that
     * datapath ID exists, or if no port with that number exists in that
     * bridge, or if the port has no external ID with that key
     */
    String getPortExternalId(long bridgeId, int portNum,
                             String externalIdKey);

    /**
     * Get an external ID associated with a given OpenFlow port number.
     *
     * @param bridgeName the name of the bridge that contains the port
     * @param portNum the OpenFlow number of the port
     * @param externalIdKey the key of the external ID to look up
     * @return the value of the external ID, or null if no bridge with that
     * name exists, or if no port with that number exists in that bridge, or if
     * the port has no external ID with that key
     */
    String getPortExternalId(String bridgeName, int portNum,
                             String externalIdKey);

    /**
     * Add a QoS of a given type.
     *
     * The available types depend on the implementation and is given in the
     * "capabilities" column in the "Open_vSwitch" table. Typically, the
     * following types are supported:
     *     "min-rate": a classifier that guarantees minimal rates
     *     "linux-htb": the Linux ``hierarchy token bucket'' classifier
     *     "linux-hfsc": the Linux "Hierarchical Fair Service Curve" classifier
     *
     * @param type the type of the QoS
     * @return a builder to set optional parameters of the QoS and add it
     */
    QosBuilder addQos(String type);

    /**
     * Update a QoS's parameters.
     *
     * @param qosUuid the UUID of the QoS to update
     * @param type the new QoS type, or null to keep the existing QoS type
     * @return a builder to reset optional parameters of the QoS and update it
     */
    QosBuilder updateQos(String qosUuid, String type);

    /**
     * Disassociate all queues from a QoS.
     *
     * Calling this convenience method is equivalent to calling updateQos() and
     * setting an empty queues map.
     *
     * @param qosUuid the UUID of the QoS which queues are to be disassociated
     * from
     */
    void clearQosQueues(String qosUuid);

    /**
     * Delete a QoS. The QoS is unset from any port on which it is set.
     *
     * @param qosUuid the UUID of the QoS to remove
     */
    void delQos(String qosUuid);

    /**
     * Set the QoS of a port.
     *
     * @param portName the name of the port to add the QoS to
     * @param qosUuid the UUID of the QoS to add to the port
     */
    void setPortQos(String portName, String qosUuid);

    /**
     * Unset the QoS of a port. If the port has no QoS set, do nothing.
     *
     * @param portName the name of the port to be disassociated from its QoS
     */
    void unsetPortQos(String portName);

    /**
     * Add a queue.
     *
     * @return a builder to set optional parameters of the queue and add it
     */
    QueueBuilder addQueue();

    /**
     * Update a queue's parameters.
     *
     * @param queueUuid the UUID of the queue to update
     * @return a builder to reset optional parameters of the queue and update
     * it
     */
    QueueBuilder updateQueue(String queueUuid);

    /**
     * Delete a queue. The queue must be disassociated from any QoSes before
     * deleting it.
     *
     * @param queueUuid the UUID of the queue to delete
     */
    void delQueue(String queueUuid);

    /**
     * Get the set of names of bridges that are associated a given external
     * ID key-value pair.
     *
     * @param key the external ID key to lookup
     * @param value the external ID to lookup
     * @return the set of names of bridges that are associated the external ID
     */
    Set<String> getBridgeNamesByExternalId(String key, String value);

    /**
     * Get the set of names of ports that are associated a given external
     * ID key-value pair.
     *
     * @param key the external ID key to lookup
     * @param value the external ID to lookup
     * @return the set of names of ports that are associated the external ID
     */
    Set<String> getPortNamesByExternalId(String key, String value);

    /**
     * Get the port number associated with the given port name.
     *
     * @param portName the port name to lookup
     * @return the set of the port numbers
     */
    Set<Short> getPortNumsByPortName(String portName);

    /**
     * Get the UUID of the port associated with the given port name.
     *
     * @param portName the name of the port to lookup
     * @return the UUID of the port
     */
    String getPortUUID(String portName);

    /**
     * Get the number of the port associated with the given UUID.
     *
     * @param portUuid the UUID of the port to lookup
     * @return the port number
     */
    short getPortNumByUUID(String portUuid);


    /**
     * Get the number of the port which interface is associated with the given
     * UUID.
     *
     * @param ifUuid the UUID of the interface associated with the port to
     *               lookup
     * @return the port number
     */
    short getPortNumByInterfaceUUID(String ifUuid);

    /**
     * Get the queue number associated with the given UUID.
     *
     * @param qosUUID the UUID of the QoS which contains the queue
     * @param queueUUID the UUID of the queue to lookup
     * @return the queue number
     */
    int getQueueNumByQueueUUID(String qosUUID, String queueUUID);

    /**
     * Close the connection.
     */
    void close();

}
