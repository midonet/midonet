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

import java.util.UUID
import java.util.concurrent.TimeUnit

import com.typesafe.config.{ConfigException, ConfigFactory}

import org.apache.http.client.fluent.Request
import org.apache.http.entity.ContentType
import org.junit.runner.RunWith
import org.reflections.Reflections
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.{ClusterConfig, ClusterNode}
import org.midonet.cluster.auth.MockAuthService
import org.midonet.cluster.services.MidonetBackendService
import org.midonet.cluster.test.util.ZookeeperTestSuite
import org.midonet.conf.{MidoNodeConfigurator, HostIdGenerator}

@RunWith(classOf[JUnitRunner])
class ConfResourceTest extends FeatureSpec
                               with Matchers
                               with BeforeAndAfterAll
                               with BeforeAndAfter
                               with GivenWhenThen with ZookeeperTestSuite {

    HostIdGenerator.useTemporaryHostId()

    private var reflections: Reflections = _
    private var backend: MidonetBackendService = _
    private var api: Vladimir = _
    private val httpPort: Int = 10000 + (Math.random() * 50000).toInt
    private val confStr =
        s"""
           |cluster.rest_api.enabled : true
           |cluster.rest_api.http_port : $httpPort
        """.stripMargin

    before {
        clearZookeeper()
        MidoNodeConfigurator(config).deployBundledConfig()
    }

    override def config = {
        ConfigFactory.parseString(confStr).withFallback(super.config)
    }

    override def beforeAll(): Unit = {
        super.beforeAll()

        reflections = new Reflections("org.midonet.cluster.rest_api",
                                      "org.midonet.cluster.services.rest_api")

        val context = ClusterNode.Context(HostIdGenerator.getHostId)
        backend = new MidonetBackendService(new MidonetBackendConfig(config),
                                                zkClient, zkClient, null)
        backend.startAsync().awaitRunning()
        api = new Vladimir(context, backend, zkClient, reflections,
                           new MockAuthService(config),
                           new ClusterConfig(config))
        api.startAsync().awaitRunning()
    }

    override def afterAll(): Unit = {
        super.afterAll()
        api.stopAsync().awaitTerminated()
        backend.stopAsync().awaitTerminated()
    }

    private def url(path: String) = {
        s"http://127.0.0.1:$httpPort/midonet-api/$path"
    }

    private def get(path: String) = ConfigFactory.parseString(
        Request.Get(url(path)).execute().returnContent().asString())

    private def post(path: String, content: String) = {
        Request.Post(url(path)).bodyString(content, ContentType.TEXT_PLAIN)
            .execute().discardContent()
    }

    private def delete(path: String) = {
        Request.Delete(url(path)).execute().returnResponse().getStatusLine
               .getStatusCode
    }

    scenario("reads, writes and deletes templates") {
        testWritableSource("conf/templates/new_template")
    }

    scenario("reads, writes and deletes per node cluster configuration") {
        testWritableSource("conf/nodes/" + UUID.randomUUID())
    }

    scenario("reads schemas") {
        When("When a GET is done on the schema URL for a known node type")
        val schema = get("conf/schemas")

        schema.isEmpty shouldBe false
        Then("the response is a valid schema with its schemaVersion key")
        schema.getInt("cluster.schemaVersion") should be > 0
    }

    scenario("assigns templates") {
        val nodeId = UUID.randomUUID().toString
        val assignment = s"""$nodeId : "the_template" """
        val template = s"""the.name : "seven" """

        When("An updated set of template mappings is posted to the API")
        post(s"conf/template-mappings/$nodeId", assignment)

        Then("getting the mappings should return the same set")
        val mappings = get("conf/template-mappings")
        mappings.getString(nodeId) shouldBe "the_template"

        var runtimeConf = get(s"conf/runtime/$nodeId")
        intercept[ConfigException.Missing] {
            runtimeConf.getString("the.name") shouldBe "seven"
        }

        When("New configuration content is posted to a template")
        post("conf/templates/the_template", template)

        Then("the runtime configuration for a node assigned to the template should include that content")
        runtimeConf = get(s"conf/runtime/$nodeId")
        runtimeConf.getString("the.name") shouldBe "seven"
    }

    scenario("lists templates") {
        val seven = s"""the.name : "seven" """
        val vandelay = s"""art.vandelay : "architect" """

        When("New two templates are created for the first time")
        val origSize = get("conf/templates").getStringList("templates").size

        post("conf/templates/seven", seven)
        post("conf/templates/vandelay", vandelay)

        Then("the list of templates should contain the new templates")
        val templates = get("conf/templates").getStringList("templates")
        templates should contain ("seven")
        templates should contain ("vandelay")
        templates should have size (2 + origSize)
    }

    def testWritableSource(path: String) {
        val newTemplateStr =
            """
              |foo.bar : 100ms
              |another.option : "string value"
              |and.yet.another : 42
            """.stripMargin

        When("A piece of configuration is POSTed")
        post(path, newTemplateStr)

        Then("the returned content is parsed as valid config and contains the same keys and values")
        val wrote = get(path)
        wrote.getDuration("foo.bar", TimeUnit.MILLISECONDS) shouldBe 100
        wrote.getString("another.option") shouldBe "string value"
        wrote.getInt("and.yet.another") shouldBe 42

        When("A piece of configuration is deleted")
        delete(path) shouldBe 200
        And("fetched again")
        val deleted = get(path)
        Then("the result is an empty configuration")
        deleted.isEmpty shouldBe true
    }
}
