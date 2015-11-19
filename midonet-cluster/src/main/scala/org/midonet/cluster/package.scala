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

import java.util.UUID

package object cluster {

    final val clusterLog = "org.midonet.cluster"

    final val authLog = "org.midonet.cluster.auth"
    final val keystoneLog = "org.midonet.cluster.auth.keystone"
    def minionLog(clazz: Class[_]): String =
        s"org.midonet.cluster.services"

    final val c3poLog =
        "org.midonet.cluster.services.neutron-importer"
    final val c3poNeutronDeserializerLog =
        "org.midonet.cluster.services.neutron-importer.importer-deserializer"
    final val c3poStorageManagerLog =
        "org.midonet.cluster.services.neutron-importer.importer-storage-manager"
    def c3poNeutronTranslatorLog(clazz: Class[_]) =
        s"org.midonet.cluster.services.neutron-importer.importer-${clazz.getSimpleName}"

    final val containersLog =
        "org.midonet.cluster.services.containers"

    final val heartbeatLog =
        "org.midonet.cluster.services.heartbeat"

    final val restApiLog =
        "org.midonet.cluster.services.rest-api"
    final val restApiJaxrsLog =
        "org.midonet.cluster.services.rest-api.jaxrs"
    final val restApiNeutronLog =
        "org.midonet.cluster.services.rest-api.neutron"
    final val restApiValidationLog =
        "org.midonet.cluster.services.rest-api.validation"
    def restApiResourceLog(clazz: Class[_]) =
        s"org.midonet.cluster.services.rest-api.resources.${clazz.getSimpleName}"

    final val topologyApiLog =
        "org.midonet.cluster.services.topology-api"
    final val topologyApiAggregatorLog =
        "org.midonet.cluster.services.topology-api.aggregator"
    final val topologyApiSessionInventoryLog =
        "org.midonet.cluster.services.topology-api.session-inventory"
    final val topologyApiServerProtocolFactoryLog =
        "org.midonet.cluster.services.topology-api.server-protocol-factory"

    final val vxgwLog = "org.midonet.cluster.services.vxgw"
    def vxgwVtepControlLog(vtepId: UUID): String =
        s"org.midonet.cluster.services.vxgw.vxgw-vtep-$vtepId"
    def vxgwMidonetLog(networkId: UUID): String =
        s"org.midonet.cluster.services.vxgw.vxgw-midonet-$networkId"

}
