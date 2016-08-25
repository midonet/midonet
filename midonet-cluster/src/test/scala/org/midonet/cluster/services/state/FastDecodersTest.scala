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

package org.midonet.cluster.services.state

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.util.Try

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.exceptions.TestFailedException
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.state.FastDecoders.MutableUUID
import org.midonet.cluster.storage.MidonetTestBackend
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.minion.Context
import org.midonet.packets.MAC

@RunWith(classOf[JUnitRunner])
class FastDecodersTest extends FeatureSpec
                               with GivenWhenThen
                               with Matchers {

    feature("MutableUUID behaves like java.util.UUID") {

        def getJavaUtilUUID(s: String): Try[UUID] = Try {
            UUID.fromString(s)
        }

        def getMutableUUID(s: String): Try[MutableUUID] = Try {
            val m = new MutableUUID
            m.fillFromString(s)
            m
        }

        def testUUID(s: String, expected: Boolean): Unit = {
            val a = getJavaUtilUUID(s)
            val b = getMutableUUID(s)
            if (a.isSuccess != expected
                || a.isSuccess != b.isSuccess
                || ( a.isSuccess &&
                    (a.get.getMostSignificantBits != b.get.getMostSignificantBits ||
                     a.get.getLeastSignificantBits != b.get.getLeastSignificantBits ||
                    a.toString != b.toString))) {
                var msg = s"UUID comparison failed for '$s':\n"
                msg += s" java.util.UUID: $a\n"
                msg += s" MutableUUID: $b\n"
                throw new Exception(msg)
            }
        }

        scenario("Valid UUIDs") {
            for (uuid <- List("47736b89-c5f3-4e90-bebf-c404b94f99f8",
                              "74550692-a9a2-43d3-8ec8-ffdc12439d94",
                              "ffffffff-ffff-ffff-ffff-ffffffffffff",
                              "74550692-A9A2-43D3-8EC8-FFDC12439D94",
                              "7736b89-c5f3-4e90-bebf-c404b94f99f1",
                              "47736b89-c5f3-e90-bebf-c404b94f99f1",
                              "00000000-0000-0000-0000-000000000000",
                              "0-1-2-3-4",
                              "0-1-2-3-4----")) {
                testUUID(uuid, true)
            }
        }

        scenario("Invalid UUIDs") {
            for (uuid <- List("7455g692-a9a2-43d3-8ec8-ffdc12439d94",
                              "47736b89-c5f3-4e90-bebf-zc404b94f99f",
                              "74550692-a9a2-43d3-8ec8--fdc12439d94",
                              "47736b89-c5f324e90-bebf-c404b94f99f1",
                              "0-1-2-3--4",
                              "00-1-2-3-",
                              "1",
                              null)) {
                testUUID(uuid, false)
            }
        }
    }

    feature("macStringToLong behaves like MAC.stringToLong") {
        def getPacketsMAC(s: String): Try[Long] = Try {
            MAC.stringToLong(s)
        }

        def getFastMAC(s: String): Try[Long] = Try {
            FastDecoders.macStringToLong(s)
        }

        def testMAC(s: String, expected: Boolean): Unit = {
            val a = getPacketsMAC(s)
            val b = getFastMAC(s)
            if (a.isSuccess!=expected
                || a.isSuccess != b.isSuccess
                || (a.isSuccess && a.get != b.get)) {
                var msg = s"MAC comparison failed for '$s':\n"
                msg += s" org.midonet.packets.MAC: $a\n"
                msg += s" FastDecoders.MAC: $b\n"
                throw new Exception(msg)
            }
        }

        scenario("Valid MACs") {
            for (mac <- List("00:00:00:00:00:00",
                             "ff:FF:fF:Ff:FF:ff",
                             "12:34:56:78:ab:cd",
                             "12:34:56:78:ab:cd:",
                             "00:00:0:00:00:00",
                             "12:34:56:78:ab:cd::::::")) {
                testMAC(mac, true)
            }
        }

        scenario("Invalid MACs") {
            for (mac <- List(":00:00:00:00:00:00",
                             "00:00::00:00:00:00",
                             "00:00:000:00:00:00",
                             "00:00::00:00:00",
                             "12:g1:56:78:ab:cd",
                             "11",
                             "",
                             null)) {
                testMAC(mac, false)
            }
        }
    }
}
