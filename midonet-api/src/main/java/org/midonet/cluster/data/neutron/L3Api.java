/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.neutron;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;

public interface L3Api {

    /**
     * Create a new router data in the data store. StatePathExistsException
     * thrown if a router with the same ID already exists.
     *
     * @param router Router object to create
     * @return Created Router object
     */
    Router createRouter(@Nonnull Router router)
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Delete a router. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Router object to delete
     */
    void deleteRouter(@Nonnull UUID id)
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Retrieve a router. Returns null if the resource does not exist.
     *
     * @param id ID of the Router object to get
     * @return Router object
     */
    Router getRouter(@Nonnull UUID id)
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Get all the routers.
     *
     * @return List of Router objects.
     */
    List<Router> getRouters()
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Update a router.  NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Router object to update
     * @return Updated Router object
     */
    Router updateRouter(@Nonnull UUID id, @Nonnull Router router)
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Add an interface on the network to link to a router.
     *
     * @param routerId ID of the router to link
     * @param routerInterface Router interface info
     * @return RouterInterface info created
     */
    RouterInterface addRouterInterface(
            @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface)
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Remove an interface on the network linked to a router.
     *
     * @param routerId ID of the router to unlink
     * @param routerInterface Router interface info
     * @return RouterInterface info removed
     */
    RouterInterface removeRouterInterface(
            @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface);

    /**
     * Create a new floating IP in the data store. StatePathExistsException
     * thrown if a floating IP with the same ID already exists.
     *
     * @param floatingIp FloatingIp object to create
     * @return Created FloatingIp object
     */
    FloatingIp createFloatingIp(@Nonnull FloatingIp floatingIp)
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Delete a floating IP. Nothing happens if the resource does not exist.
     *
     * @param id ID of the FloatingIp object to delete
     */
    void deleteFloatingIp(@Nonnull UUID id)
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Retrieve a floating IP. Returns null if the resource does not exist.
     *
     * @param id ID of the FloatingIp object to get
     * @return Router object
     */
    FloatingIp getFloatingIp(@Nonnull UUID id)
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Get all the floating IP.
     *
     * @return List of FloatingIp objects.
     */
    List<FloatingIp> getFloatingIps()
            throws ConflictHttpException, NotFoundHttpException;

    /**
     * Update a floating IP..  NoStatePathException is thrown if the resource
     * does not exist.
     *
     * @param id ID of the FloatingIp object to update
     * @return Updated FloatingIp object
     */
    FloatingIp updateFloatingIp(@Nonnull UUID id,
                                @Nonnull FloatingIp floatingIp)
            throws ConflictHttpException, NotFoundHttpException;
}
