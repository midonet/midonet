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

package org.midonet.cluster.services.rest_api.neutron.plugin

import java.util
import java.util.UUID
import javax.annotation.Nonnull

import org.midonet.cluster.rest_api.neutron.models.{FloatingIp, Router, RouterInterface}
import org.midonet.cluster.rest_api.{ConflictHttpException, NotFoundHttpException}

trait L3Api {

    /**
     * Create a new router data in the data store.
     *
     * @param router Router object to create
     * @return Created Router object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createRouter(@Nonnull router: Router): Router

    /**
     * Delete a router. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Router object to delete
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteRouter(@Nonnull id: UUID)

    /**
     * Retrieve a router. Returns null if the resource does not exist.
     *
     * @param id ID of the Router object to get
     * @return Router object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getRouter(@Nonnull id: UUID): Router

    /**
     * Get all the routers.
     *
     * @return List of Router objects.
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getRouters: util.List[Router]

    /**
     * Update a router.
     *
     * @param id ID of the Router object to update
     * @return Updated Router object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateRouter(@Nonnull id: UUID, @Nonnull router: Router): Router

    /**
     * Add an interface on the network to link to a router.
     *
     * @param routerId ID of the router to link
     * @param routerInterface Router interface info
     * @return RouterInterface info created
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def addRouterInterface(@Nonnull routerId: UUID,
                           @Nonnull routerInterface: RouterInterface): RouterInterface

    /**
     * Remove an interface on the network linked to a router.
     *
     * @param routerId ID of the router to unlink
     * @param routerInterface Router interface info
     * @return RouterInterface info removed
     */
    def removeRouterInterface(@Nonnull routerId: UUID,
                              @Nonnull routerInterface: RouterInterface): RouterInterface

    /**
     * Create a new floating IP in the data store.
     *
     * @param floatingIp FloatingIp object to create
     * @return Created FloatingIp object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createFloatingIp(@Nonnull floatingIp: FloatingIp): FloatingIp

    /**
     * Delete a floating IP. Nothing happens if the resource does not exist.
     *
     * @param id ID of the FloatingIp object to delete
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteFloatingIp(@Nonnull id: UUID)

    /**
     * Retrieve a floating IP. Returns null if the resource does not exist.
     *
     * @param id ID of the FloatingIp object to get
     * @return Router object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getFloatingIp(@Nonnull id: UUID): FloatingIp

    /**
     * Get all the floating IP.
     *
     * @return List of FloatingIp objects.
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getFloatingIps: util.List[FloatingIp]

    /**
     * Update a floating IP.
     *
     * @param id ID of the FloatingIp object to update
     * @return Updated FloatingIp object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateFloatingIp(@Nonnull id: UUID,
                         @Nonnull floatingIp: FloatingIp): FloatingIp
}