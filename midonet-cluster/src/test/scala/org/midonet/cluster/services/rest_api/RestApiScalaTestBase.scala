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

package org.midonet.cluster.services.rest_api

import java.net.URI
import java.util.UUID

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

import com.sun.jersey.test.framework.AppDescriptor

import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.rest_api.models.Rule.{RuleAction, RuleType}
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase

class RestApiScalaTestBase(appDesc: AppDescriptor)
    extends RestApiTestBase(appDesc) with HttpRequestChecks {

    private lazy val typeUris = Map[Class[_], URI](
        classOf[Chain] -> app.getChains,
        classOf[Host] -> app.getHosts
    )

    def create[T <: UriResource](t: T): T = {
        val uri = postAndAssertOk(t, typeUris(t.getClass))
        getAndAssertOk(uri)(ClassTag(t.getClass))
    }

    def makeChain(id: UUID = UUID.randomUUID(),
                  ruleIds: Seq[UUID] = Seq()): Chain = {
        val chain = new Chain
        chain.id = id
        chain.ruleIds = ruleIds
        chain.setBaseUri(app.getUri)
        chain
    }

    def makeRule(chainId: UUID,
                 ruleType: RuleType = RuleType.LITERAL,
                 action: RuleAction = RuleAction.ACCEPT,
                 id: UUID = UUID.randomUUID()): Rule = {
        val r = (ruleType, action) match {
            case (RuleType.LITERAL, RuleAction.ACCEPT) => new AcceptRule
            case _ => fail(s"$ruleType/$action not implemented in makeRule()")
        }
        r.id = id
        r.setBaseUri(app.getUri)
        r
    }

    def makeHost(id: UUID = UUID.randomUUID(),
                 name: String = "test_host") : Host = {
        val h = new Host()
        h.id = id
        h.name = name
        h.setBaseUri(app.getUri)
        h
    }
}
