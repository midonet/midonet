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

package org.midonet.midolman.host.scanner

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.midolman.host.scanner.test.InterfaceScannerIntegrationTest
import org.midonet.util.IntegrationTests.{LazyTest, LazyTestSuite}

/**
 * InterfaceScannerTest wraps InterfaceScannerIntegration and gives reports
 * for tests in it.
 */
@RunWith(classOf[JUnitRunner])
class InterfaceScannerTest extends FeatureSpec
                           with BeforeAndAfterAll
                           with Matchers
                           with InterfaceScannerIntegrationTest {
    override lazy val scanner = DefaultInterfaceScanner()

    override val logger: Logger = Logger(LoggerFactory.getLogger(
        classOf[InterfaceScannerTest]))

    private def prepareTests(): Unit =
        super.start()

    override def afterAll(): Unit =
        super.stop()

    def testsToScinarios(tests: LazyTestSuite): Unit = {
        tests.foreach { lazyTest: LazyTest =>
            val (desc, test) = lazyTest()
            scenario(desc) {
                Await.result(test, 2.seconds)
            }
        }
    }

    feature("rtnetlink integration") {
        prepareTests()
        testsToScinarios(LinkTests)
        testsToScinarios(AddrTests)
        testsToScinarios(SubscriptionTests)
    }
}
