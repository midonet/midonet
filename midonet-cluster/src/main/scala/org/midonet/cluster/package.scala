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

package org.midonet

package object cluster {

    final val ClusterLog = "org.midonet.cluster"

    final val AuthLog = "org.midonet.cluster.auth"
    final val KeystoneLog = "org.midonet.cluster.auth.keystone"

    final val C3poLog =
        "org.midonet.cluster.services.neutron-importer"
    final val C3poNeutronDeserializerLog =
        "org.midonet.cluster.services.neutron-importer.importer-deserializer"
    final val C3poStorageManagerLog =
        "org.midonet.cluster.services.neutron-importer.importer-storage-manager"
    def c3poNeutronTranslatorLog(clazz: Class[_]) =
        s"org.midonet.cluster.services.neutron-importer.importer-${clazz.getSimpleName}"

    final val ContainersLog =
        "org.midonet.cluster.services.containers"

    final val HeartbeatLog =
        "org.midonet.cluster.services.heartbeat"

    final val RecyclerLog =
        "org.midonet.cluster.services.recycler"

    final val RestApiLog =
        "org.midonet.cluster.services.rest-api"
    final val RestApiJaxrsLog =
        "org.midonet.cluster.services.rest-api.jaxrs"
    final val RestApiNeutronLog =
        "org.midonet.cluster.services.rest-api.neutron"
    final val RestApiValidationLog =
        "org.midonet.cluster.services.rest-api.validation"
    def restApiResourceLog(clazz: Class[_]) =
        s"org.midonet.cluster.services.rest-api.resources.${clazz.getSimpleName}"

    final val StateProxyLog =
        "org.midonet.cluster.services.state-proxy"
    final val StateProxyServerLog =
        "org.midonet.cluster.services.state-proxy.server"
    final val StateProxyCacheLog =
        "org.midonet.cluster.services.state-proxy.cache"

    final val TopologyApiLog =
        "org.midonet.cluster.services.topology-api"
    final val TopologyApiAggregatorLog =
        "org.midonet.cluster.services.topology-api.aggregator"
    final val TopologyApiSessionInventoryLog =
        "org.midonet.cluster.services.topology-api.session-inventory"
    final val TopologyApiServerProtocolFactoryLog =
        "org.midonet.cluster.services.topology-api.server-protocol-factory"

    final val TopologyCacheLog =
        "org.midonet.cluster.services.topology-cache"

    final val VxgwLog = "org.midonet.cluster.services.vxgw"
    final val VxgwVtepLog = "org.midonet.cluster.services.vxgw.vtep"

}
