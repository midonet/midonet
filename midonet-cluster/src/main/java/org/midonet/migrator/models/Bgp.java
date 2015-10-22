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

package org.midonet.migrator.models;

import java.util.UUID;

import com.google.common.base.MoreObjects;

import org.midonet.packets.IPv4Addr;

public class Bgp {

    public UUID id;
    public UUID portId;
    public int localAs;
    public int peerAs;
    public IPv4Addr peerAddress;

    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("id", id)
            .add("portId", portId)
            .add("localAs", localAs)
            .add("peerAs", peerAs)
            .add("peerAddress", peerAddress)
            .toString();
    }

}
