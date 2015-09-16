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

import org.midonet.cluster.rest_api.neutron.models.{Network, Port, Subnet}
import org.midonet.cluster.rest_api.{ConflictHttpException, NotFoundHttpException}

trait NetworkApi {

    /**
     * Create a new network data in the data store.
     *
     * @param network Network object to create
     * @return Created Network object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createNetwork(@Nonnull network: Network): Network

    /**
     * Create multiple networks atomically.
     *
     * @param networks Network objects to create
     * @return Created Network objects
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createNetworkBulk(@Nonnull networks: util.List[Network])
    : util.List[Network]

    /**
     * Delete a network. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Network object to delete
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteNetwork(@Nonnull id: UUID)

    /**
     * Retrieve a network. Returns null if the resource does not exist.
     *
     * @param id ID of the Network object to get
     * @return Network object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getNetwork(@Nonnull id: UUID): Network

    /**
     * Get all the networks.
     *
     * @return util.List of Network objects.
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getNetworks: util.List[Network]

    /**
     * Update a network.
     *
     * @param id ID of the Network object to update
     * @return Updated Network object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateNetwork(@Nonnull id: UUID, @Nonnull network: Network): Network

    /**
     * Create a new subnet data in the data store.
     *
     * @param subnet Network object to create
     * @return Created Subnet object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createSubnet(@Nonnull subnet: Subnet): Subnet

    /**
     * Create multiple subnets atomically.
     *
     * @param subnets Subnet objects to create
     * @return Created Subnet objects
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createSubnetBulk(@Nonnull subnets: util.List[Subnet])
    : util.List[Subnet]

    /**
     * Delete a subnet.  Nothing happens if the resource does not exist.
     *
     * @param id ID of the Subnet object to delete
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteSubnet(@Nonnull id: UUID)

    /**
     * Retrieve a subnet.  Returns null if the resource does not exist.
     *
     * @param id ID of the Subnet object to get
     * @return Subnet object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getSubnet(@Nonnull id: UUID): Subnet

    /**
     * Get all the subnets.
     *
     * @return util.List of Subnet objects.
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getSubnets: util.List[Subnet]

    /**
     * Update a subnet.
     *
     * @param id ID of the Subnet object to update
     * @return Updated Subnet object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateSubnet(@Nonnull id: UUID, @Nonnull subnet: Subnet): Subnet

    /**
     * Create a new port data in the data store.
     *
     * @param port port object to create
     * @return Created Port object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createPort(@Nonnull port: Port): Port

    /**
     * Create multiple ports atomically.
     *
     * @param ports Port objects to create
     * @return Created Port objects
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createPortBulk(@Nonnull ports: util.List[Port]): util.List[Port]

    /**
     * Delete a port. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Port object to delete
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deletePort(@Nonnull id: UUID)

    /**
     * Retrieve a port. Returns null if the resource does not exist.
     *
     * @param id ID of the Port object to delete
     * @return Port object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getPort(@Nonnull id: UUID): Port

    /**
     * Get all the ports.
     *
     * @return util.List of Port objects.
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getPorts: util.List[Port]

    /**
     * Update a port.
     *
     * @param id ID of the Port object to update
     * @return Updated Port object
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updatePort(@Nonnull id: UUID, @Nonnull port: Port): Port
}