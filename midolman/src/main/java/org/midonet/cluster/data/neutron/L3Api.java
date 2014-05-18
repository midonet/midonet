/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.cluster.data.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public interface L3Api {

    /**
     * Create a new router data in the data store. StatePathExistsException
     * thrown if a router with the same ID already exists.
     *
     * @param router Router object to create
     * @return Created Router object
     */
    public Router createRouter(@Nonnull Router router)
            throws StateAccessException, SerializationException;

    /**
     * Delete a router. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Router object to delete
     */
    public void deleteRouter(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Retrieve a router. Returns null if the resource does not exist.
     *
     * @param id ID of the Router object to get
     * @return Router object
     */
    public Router getRouter(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get all the routers.
     *
     * @return List of Router objects.
     */
    public List<Router> getRouters()
            throws StateAccessException, SerializationException;

    /**
     * Update a router.  NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Router object to update
     * @return Updated Router object
     */
    public Router updateRouter(@Nonnull UUID id, @Nonnull Router router)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Add an interface on the network to link to a router.
     *
     * @param routerId ID of the router to link
     * @param routerInterface Router interface info
     * @return RouterInterface info created
     */
    public RouterInterface addRouterInterface(
            @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface)
            throws StateAccessException, SerializationException;

    /**
     * Remove an interface on the network linked to a router.
     *
     * @param routerId ID of the router to unlink
     * @param routerInterface Router interface info
     * @return RouterInterface info removed
     */
    public RouterInterface removeRouterInterface(
            @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface);
}
