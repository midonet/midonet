// Copyright (c) 2013 Midokura SARL, All Rights Reserved.

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
