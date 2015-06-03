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


public interface NetworkApi {

    /**
     * Create a new network data in the data store. StatePathExistsException
     * thrown if a network with the same ID already exists.
     *
     * @param network Network object to create
     * @return Created Network object
     */
    Network createNetwork(@Nonnull Network network)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Create multiple networks atomically.
     *
     * @param networks Network objects to create
     * @return Created Network objects
     */
    List<Network> createNetworkBulk(@Nonnull List<Network> networks)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Delete a network. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Network object to delete
     */
    void deleteNetwork(@Nonnull UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Retrieve a network. Returns null if the resource does not exist.
     *
     * @param id ID of the Network object to get
     * @return Network object
     */
    Network getNetwork(@Nonnull UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Get all the networks.
     *
     * @return List of Network objects.
     */
    List<Network> getNetworks()
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Update a network. NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Network object to update
     * @return Updated Network object
     */
    Network updateNetwork(@Nonnull UUID id, @Nonnull Network network)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Create a new subnet data in the data store.  StatePathExistsException
     * thrown if the subnet with the same ID already exists.
     *
     * @param subnet Network object to create
     * @return Created Subnet object
     */
    Subnet createSubnet(@Nonnull Subnet subnet)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Create multiple subnets atomically.
     *
     * @param subnets Subnet objects to create
     * @return Created Subnet objects
     */
    List<Subnet> createSubnetBulk(@Nonnull List<Subnet> subnets)
        throws ConflictHttpException, NotFoundHttpException;


    /**
     * Delete a subnet.  Nothing happens if the resource does not exist.
     *
     * @param id ID of the Subnet object to delete
     */
    void deleteSubnet(@Nonnull UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Retrieve a subnet.  Returns null if the resource does not exist.
     *
     * @param id ID of the Subnet object to get
     * @return Subnet object
     */
    Subnet getSubnet(@Nonnull UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Get all the subnets.
     *
     * @return List of Subnet objects.
     */
    List<Subnet> getSubnets()
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Update a subnet.  NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Subnet object to update
     * @return Updated Subnet object
     */
    Subnet updateSubnet(@Nonnull UUID id, @Nonnull Subnet subnet)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Create a new port data in the data store. StatePathExistsException
     * thrown if the port with the same ID already exists.
     *
     * @param port port object to create
     * @return Created Port object
     */
    Port createPort(@Nonnull Port port)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Create multiple ports atomically.
     *
     * @param ports Port objects to create
     * @return Created Port objects
     */
    List<Port>  createPortBulk(@Nonnull List<Port> ports)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Delete a port. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Port object to delete
     */
    void deletePort(@Nonnull UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Retrieve a port. Returns null if the resource does not exist.
     *
     * @param id ID of the Port object to delete
     * @return Port object
     */
    Port getPort(@Nonnull UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Get all the ports.
     *
     * @return List of Port objects.
     */
    List<Port> getPorts()
        throws ConflictHttpException, NotFoundHttpException;

    /**
     * Update a port. NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Port object to update
     * @return Updated Port object
     */
    Port updatePort(@Nonnull UUID id, @Nonnull Port port)
        throws ConflictHttpException, NotFoundHttpException;
}
