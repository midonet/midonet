/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.topology.devices

import org.midonet.cluster.data.{Zoom, ZoomClass, ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology

@ZoomClass(clazz = classOf[Topology.Port.VppBinding])
final class VppBinding @Zoom()(
    @ZoomField(name = "interface_name") val interfaceName: String)
    extends ZoomObject {

    override def toString = s"VppBinding [interfaceName=$interfaceName]"

    override def equals(obj: Any): Boolean = {
        obj match {
            case binding: VppBinding => binding.interfaceName == interfaceName
            case _ => false
        }
    }

    override def hashCode: Int = {
        interfaceName.hashCode
    }

}
