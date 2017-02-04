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
package org.midonet.odp

import scala.concurrent.duration._
import scala.concurrent.{Future, Await}
import scala.sys.process._

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.test.{DatapathTest => DpTest, PortTest, MegaFlowTest, FlowTest}
import org.midonet.util.IntegrationTests

@RunWith(classOf[JUnitRunner])
class OvsTest extends FeatureSpec with BeforeAndAfterAll with Matchers with
        DpTest with FlowTest with MegaFlowTest with PortTest {

    var baseConnection: OvsDatapathConnection = _
    var con: OvsConnectionOps = _

    def beforeTests() {
        "./src/main/resources/ovs_integration_create.sh".!
        baseConnection = DatapathClient.createConnection()
        con = new OvsConnectionOps(baseConnection)
    }

    override def afterAll() {
        baseConnection.getChannel.close()
        "./src/main/resources/ovs_integration_del.sh".!
    }

    def checkResults(ts: IntegrationTests.TestSuite): Seq[Future[Any]] =
        ts.map { case (desc, result) =>
            scenario(desc) {
                Await.result(result, 2 seconds)
            }
            result
        }

    feature("OVS integration") {
        beforeTests()
        val (dpF, cleanupTests, dpTests) = datapathTests()
        val fs = checkResults(dpTests) ++
                 checkResults(dpPortTests(dpF)) ++
                 checkResults(flowTests(dpF)) ++
                 checkResults(wflowTests(dpF))
        fs andThen { _ => checkResults(dpCleanup(cleanupTests)) }
    }
}
