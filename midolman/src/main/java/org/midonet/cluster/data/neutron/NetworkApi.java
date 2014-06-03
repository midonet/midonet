/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.cluster.data.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;


public interface NetworkApi {

    /**
     * Create a new network data in the data store. StatePathExistsException
     * thrown if a network with the same ID already exists.
     *
     * @param network Network object to create
     * @return Created Network object
     */
    Network createNetwork(@Nonnull Network network)
            throws StateAccessException, SerializationException;

    /**
     * Create multiple networks atomically.
     *
     * @param networks Network objects to create
     * @return Created Network objects
     */
    List<Network> createNetworkBulk(@Nonnull List<Network> networks)
            throws StateAccessException, SerializationException;

    /**
     * Delete a network. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Network object to delete
     */
    void deleteNetwork(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Retrieve a network. Returns null if the resource does not exist.
     *
     * @param id ID of the Network object to get
     * @return Network object
     */
    Network getNetwork(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get all the networks.
     *
     * @return List of Network objects.
     */
    List<Network> getNetworks()
            throws StateAccessException, SerializationException;

    /**
     * Update a network. NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Network object to update
     * @return Updated Network object
     */
    Network updateNetwork(@Nonnull UUID id, @Nonnull Network network)
            throws StateAccessException, SerializationException,
            BridgeZkManager.VxLanPortIdUpdateException;

    /**
     * Create a new subnet data in the data store.  StatePathExistsException
     * thrown if the subnet with the same ID already exists.
     *
     * @param subnet Network object to create
     * @return Created Subnet object
     */
    Subnet createSubnet(@Nonnull Subnet subnet)
            throws StateAccessException, SerializationException;

    /**
     * Create multiple subnets atomically.
     *
     * @param subnets Subnet objects to create
     * @return Created Subnet objects
     */
    List<Subnet> createSubnetBulk(@Nonnull List<Subnet> subnets)
            throws StateAccessException, SerializationException;


    /**
     * Delete a subnet.  Nothing happens if the resource does not exist.
     *
     * @param id ID of the Subnet object to delete
     */
    void deleteSubnet(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Retrieve a subnet.  Returns null if the resource does not exist.
     *
     * @param id ID of the Subnet object to get
     * @return Subnet object
     */
    Subnet getSubnet(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get all the subnets.
     *
     * @return List of Subnet objects.
     */
    List<Subnet> getSubnets()
            throws StateAccessException, SerializationException;

    /**
     * Update a subnet.  NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Subnet object to update
     * @return Updated Subnet object
     */
    Subnet updateSubnet(@Nonnull UUID id, @Nonnull Subnet subnet)
            throws StateAccessException, SerializationException;

    /**
     * Create a new port data in the data store. StatePathExistsException
     * thrown if the port with the same ID already exists.
     *
     * @param port port object to create
     * @return Created Port object
     */
    Port createPort(@Nonnull Port port)
            throws StateAccessException, SerializationException;

    /**
     * Create multiple ports atomically.
     *
     * @param ports Port objects to create
     * @return Created Port objects
     */
    List<Port>  createPortBulk(@Nonnull List<Port> ports)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;
    /**
     * Delete a port. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Port object to delete
     */
    void deletePort(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Retrieve a port. Returns null if the resource does not exist.
     *
     * @param id ID of the Port object to delete
     * @return Port object
     */
    Port getPort(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get all the ports.
     *
     * @return List of Port objects.
     */
    List<Port> getPorts()
            throws StateAccessException, SerializationException;

    /**
     * Update a port. NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Port object to update
     * @return Updated Port object
     */
    Port updatePort(@Nonnull UUID id, @Nonnull Port port)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;
}
