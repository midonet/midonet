/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.rules

import java.util.UUID
import org.junit.runner.RunWith
import org.scalatest.{Matchers, Suite}
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import collection.JavaConversions._
import org.midonet.midolman.version.serialization.JsonVersionZkSerializer

@RunWith(classOf[JUnitRunner])
class RuleListTest extends Suite with Matchers {

    val log =
        LoggerFactory.getLogger(classOf[RuleListTest])

    def testRuleListSerialization() {

        val emptyRl = new RuleList();

        val rule1 = UUID.randomUUID();
        val rule2 = UUID.randomUUID();
        val rule3 = UUID.randomUUID();

        val listUUIDs = List(rule1, rule2, rule3)
        val fullRl = new RuleList(listUUIDs);

        val reorderedListUUIDs = List(rule2, rule1, rule3)
        val reorderedRl = new RuleList(reorderedListUUIDs);

        val emptyJson = JsonVersionZkSerializer.objToJsonString(emptyRl)
        val fullJson = JsonVersionZkSerializer.objToJsonString(fullRl)

        val retrievedEmptyRl = JsonVersionZkSerializer.jsonStringToObj(emptyJson, classOf[RuleList])
        val retrievedFullRl = JsonVersionZkSerializer.jsonStringToObj(fullJson, classOf[RuleList])

        retrievedEmptyRl should be equals emptyRl
        retrievedFullRl should be equals fullRl
        retrievedFullRl should not equals reorderedRl
    }
}
