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

package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import com.fasterxml.jackson.databind.JsonNode

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{GatewayDevice => GatewayDeviceType, L2GatewayConnection => L2ConnType, RemoteMacEntry => RemoteMacEntryType}
import org.midonet.cluster.models.Neutron.{GatewayDevice, L2GatewayConnection, RemoteMacEntry}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class BgpTranslationIT extends C3POMinionTestBase {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true
}
