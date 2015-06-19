/* * Copyright 2015 Midokura SARL
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
package org.midonet.odp

import scala.concurrent.duration._
import scala.concurrent.{Future, Await}
import scala.sys.process._
import scala.util.Try

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.test.{DatapathTest => DpTest, PortTest, MegaFlowTest, FlowTest}
import org.midonet.util.IntegrationTests


@RunWith(classOf[JUnitRunner])
class OvsTest extends FeatureSpec with BeforeAndAfterAll with ShouldMatchers with
        DpTest with FlowTest with MegaFlowTest with PortTest {

    import org.midonet.util.IntegrationTests._

    var baseConnection: OvsDatapathConnection = _
    var con: OvsConnectionOps = _

    var datapaths: List[String] = Nil

    def beforeTests() {
        "./src/main/resources/ovs_integration_create.sh".!
        baseConnection = DatapathClient.createConnection()
        con = new OvsConnectionOps(baseConnection)
    }

    override def afterAll() {
        for (dpName <- datapaths) {
            Try(Await.result(con delDp dpName, 1 second))
        }

        baseConnection.getChannel.close()
        "./src/main/resources/ovs_integration_del.sh".!
    }

    private def cleanup(dps: Seq[(String, Future[Datapath])]): Unit = {
        for ((desc, dp) <- dps) {
            Await.result(dp, 2 seconds)
        }
    }

    def checkResults(dpName: String, test: (Future[Datapath]) => IntegrationTests.TestSuite): Unit = {
        val dpF = con createDp dpName
        datapaths ::= dpName
        for ((desc, result) <- test(dpF)) {
            scenario(desc) {
                Await.result(result, 2 seconds)
            }
        }
    }

    def checkResults(ts: IntegrationTests.TestSuite): Unit = {
        for ((desc, result) <- ts) {
            scenario(desc) {
                Await.result(result, 2 seconds)
            }
        }
    }

    feature("OVS integration") {
        beforeTests()
        checkResults("portTests", dpPortTests)
        checkResults("flowTests", flowTests)
        checkResults("megaflowTests", wflowTests)
        val (_, cleanupTests, dpTests) = datapathTests()
        checkResults(dpTests)
        checkResults(dpCleanup(cleanupTests))
    }
}
