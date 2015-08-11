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

package org.midonet.cluster.services.rest_api.resources.federation

object FederationMediaTypes {

    final val APPLICATION_JSON_V1 = "application/vnd.org.midonet.Application-v1+json"
    final val APPLICATION_ERROR_JSON = "application/vnd.org.midonet.Error-v1+json"

    final val VTEP_GROUP_JSON = "application/vnd.org.midonet.VtepGroup-v1+json"
    final val VTEP_GROUP_COLLECTION_JSON = "application/vnd.org.midonet.collection.VtepGroup-v1+json"
    final val VXLAN_SEGMENT_JSON = "application/vnd.org.midonet.VxlanSegment-v1+json"
    final val VXLAN_SEGMENT_COLLECTION_JSON = "application/vnd.org.midonet.collection.VxlanSegment-v1+json"
    final val MIDONET_VTEP_JSON = "application/vnd.org.midonet.MidonetVtep-v1+json"
    final val MIDONET_VTEP_COLLECTION_JSON = "application/vnd.org.midonet.collection.MidonetVtep-v1+json"
    final val OVSDB_VTEP_JSON = "application/vnd.org.midonet.OvsdbVtep-v1+json"
    final val OVSDB_VTEP_COLLECTION_JSON = "application/vnd.org.midonet.collection.OvsdbVtep-v1+json"
}
