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

package org.midonet.cluster.services.rest_api.resources

import org.junit.Test

import org.midonet.cluster.rest_api.models.LogResource
import org.midonet.cluster.rest_api.models.LogResource.LogType
import org.midonet.cluster.rest_api.rest_api.FuncTest
import org.midonet.cluster.services.rest_api.RestApiScalaTestBase

import scala.collection.JavaConverters._

class TestLogResource extends RestApiScalaTestBase(FuncTest.appDesc) {

    @Test
    def testLogResourceCrud(): Unit = {
        val lr = create(makeLogResource(fileName = Some("test.log")))
        lr.`type` shouldBe LogType.FILE
        lr.fileName shouldBe "test.log"
        lr.ipAddress shouldBe null

        lr.fileName = "new.log"
        putAndAssertOk(lr)
        val lr2 = getAndAssertOk[LogResource](lr.getUri)
        lr2.fileName shouldBe "new.log"
        lr2.id shouldBe lr.id

        deleteAndAssertGone[LogResource](lr2.getUri)
    }

    @Test
    def testLogOnChain(): Unit = {
        val lr1 = create(makeLogResource(fileName = Some("log1.log")))
        val lr2 = create(makeLogResource(fileName = Some("log2.log")))

        val chain = create(makeChain(logResourceIds = Seq(lr1.id, lr2.id),
                                     logMetadata = Map("a" -> "1", "b" -> "2")))
        chain.logMetadata.asScala shouldBe Map("a" -> "1", "b" -> "2")
        chain.logResourceIds.asScala should contain only(lr1.id, lr2.id)
    }
}
