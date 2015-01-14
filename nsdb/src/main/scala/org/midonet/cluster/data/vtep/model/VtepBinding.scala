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

package org.midonet.cluster.data.vtep.model

import java.util.{Objects, UUID}

/**
 * A representation of a vtep port binding
 */
case class VtepBinding(portName: String, vlanId: Short, networkId: UUID) {
    // Sanity checks
    if (portName == null || networkId == null)
        throw new NullPointerException("null vtep binding values")

    // TODO: remove when all legacy code uses portName, vlanId and networkId
    def getPortName = portName
    def getVlanId = vlanId
    def getNetworkId = networkId

    override def equals(o: Any): Boolean = o match {
        case that: VtepBinding =>
            that.vlanId == vlanId &&
            that.networkId == networkId &&
            that.portName == portName
        case _ => false
    }

    override def hashCode: Int = Objects.hash(vlanId.asInstanceOf[Integer],
                                              networkId, portName)

    override def toString: String =
        s"VtepBinding{portname='$portName', vlanId=$vlanId, networkId=$networkId}"

}
