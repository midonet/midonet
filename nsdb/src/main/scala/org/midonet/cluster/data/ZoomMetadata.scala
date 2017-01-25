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

package org.midonet.cluster.data

object ZoomMetadata {

    /**
      * Indicates the owner for a change in storage to indicate the
      * data provenance.
      */
    case class ZoomOwner(id: Int, name: String) {
        override def toString: String = s"$id:$name"
    }

    object ZoomOwner {
        final val None = ZoomOwner(0, "none")
        final val ClusterApi = ZoomOwner(0x101, "cluster:api")
        final val ClusterNeutron = ZoomOwner(0x102, "cluster:neutron")
        final val ClusterContainers = ZoomOwner(0x103, "cluster:containers")
        final val AgentBinding = ZoomOwner(0x201, "agent:binding")
        final val AgentHaProxy = ZoomOwner(0x202, "agent:haproxy")
    }

    object ZoomChange extends Enumeration {
        type ZoomChange = Value
        val Data = Value(0x1, "data")
        val BackReference = Value(0x2, "backreference")
    }

}
