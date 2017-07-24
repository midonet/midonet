/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.cache.state;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Topology;

public class HostStateOwnershipTest {

    @Test
    public void testGood() {
        // Given a host state owner.
        StateOwnership stateOwner = new HostStateOwnership();

        // And a host.
        UUID id = UUID.randomUUID();
        Topology.Host host = Topology.Host.newBuilder()
            .setId(Commons.UUID.newBuilder()
                       .setMsb(id.getMostSignificantBits())
                       .setLsb(id.getLeastSignificantBits()).build())
            .build();

        // When passing the object to state owner instance.
        UUID owner = stateOwner.ownerOf(id, host);

        // Then the instance returns the owner UUID.
        Assert.assertEquals(owner, id);
    }

}
