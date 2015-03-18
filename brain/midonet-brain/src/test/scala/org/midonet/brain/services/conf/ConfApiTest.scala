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

package org.midonet.brain.services.conf

import java.util.UUID
import java.util.concurrent.TimeUnit

import com.typesafe.config.{ConfigException, ConfigFactory}
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.apache.http.client.fluent.Request
import org.apache.http.entity.ContentType
import org.junit.runner.RunWith

import org.midonet.brain.{BrainConfig, ClusterNode}
import org.midonet.conf.{MidoTestConfigurator, HostIdGenerator}
import org.scalatest.junit.JUnitRunner
import org.scalatest._

trait ZookeeperTestSuite extends BeforeAndAfterAll with BeforeAndAfter { this: Suite =>
    var zkServer: TestingServer = _
    var zkClient: CuratorFramework = _

    val ZK_PORT: Int = 10000 + (Math.random() * 50000).toInt
    val ZK_ROOT = "/test"

    protected def config = MidoTestConfigurator.forBrains(ConfigFactory.parseString(
        s"""
          |zookeeper.zookeeper_hosts : "127.0.0.1:$ZK_PORT"
          |zookeeper.root_key : "$ZK_ROOT"
        """.stripMargin))

    override def beforeAll(): Unit = {
        super.beforeAll()
        if (zkServer eq null) {
            zkServer = new TestingServer(ZK_PORT)
            zkServer.start()
        }

        zkClient = CuratorFrameworkFactory.newClient(
                zkServer.getConnectString, new RetryNTimes(1, 1000))
        zkClient.start()
        zkClient.create().forPath(ZK_ROOT)
    }

    override def afterAll(): Unit = {
        if (zkClient ne null)
            zkClient.close()
        if (zkServer ne null)
            zkServer.close()
    }

    protected def clearZookeeper(): Unit = {
        try {
            zkClient.delete().deletingChildrenIfNeeded().forPath(ZK_ROOT)
        } catch {
            case e: Exception => // ignore
        }
    }
}


@RunWith(classOf[JUnitRunner])
class ConfApiTest extends FeatureSpecLike
                            with Matchers
                            with BeforeAndAfterAll
                            with GivenWhenThen
                            with ZookeeperTestSuite {

    HostIdGenerator.useTemporaryHostId()

    var confMinion: ConfMinion = _

    val HTTP_PORT: Int = 10000 + (Math.random() * 50000).toInt

    private val confStr =
        s"""
          |conf_api.enabled : true
          |conf_api.http_port : ${HTTP_PORT}
        """.stripMargin

    override def config = ConfigFactory.parseString(confStr).withFallback(super.config)

    override def beforeAll(): Unit = {
        super.beforeAll()
        val context = ClusterNode.Context(HostIdGenerator.getHostId, true)
        confMinion = new ConfMinion(context, new BrainConfig(config))
        confMinion.doStart()
    }

    override def afterAll(): Unit = {
        super.afterAll()
        confMinion.doStop()
    }

    private def url(path: String) = s"http://127.0.0.1:$HTTP_PORT/$path"

    private def get(path: String) = ConfigFactory.parseString(
        Request.Get(url(path)).execute().returnContent().asString())

    private def post(path: String, content: String) = {
        Request.Post(url(path)).bodyString(content, ContentType.TEXT_PLAIN).execute()
    }

    private def delete(path: String) =
        Request.Delete(url(path)).execute().returnResponse().getStatusLine.getStatusCode

    scenario("reads, writes and deletes brain templates") {
        testWritableSource("conf/brain/template/new_template")
    }

    scenario("reads, writes and deletes per node brain configuration") {
        testWritableSource("conf/brain/node/" + UUID.randomUUID())
    }

    scenario("reads, writes and deletes agent templates") {
        testWritableSource("conf/agent/template/new_template")
    }

    scenario("reads, writes and deletes per node agent configuration") {
        testWritableSource("conf/agent/node/" + UUID.randomUUID())
    }

    scenario("reads schemas") {
        When("When a GET is done on the schema URL for a known node type")
        val schema = get("conf/brain/schema")

        schema.isEmpty should be (false)
        Then("the response is a valid schema with its schemaVersion key")
        schema.getInt("schemaVersion") should be > 0
    }

    scenario("assigns templates") {
        val nodeId = UUID.randomUUID().toString
        val assignment = s"""$nodeId : "the_template" """
        val template = s"""the.name : "seven" """

        When("An updated set of template mappings is posted to the API")
        post("conf/brain/template-mappings", assignment)

        val mappings = get("conf/brain/template-mappings")
        Then("getting the mappings should return the same set")
        mappings.getString(nodeId) should be ("the_template")

        var runtimeConf = get(s"conf/brain/runtime-config/$nodeId")
        intercept[ConfigException.Missing] {
            runtimeConf.getString("the.name") should be ("seven")
        }

        When("New configuration content is posted to a template")
        post("conf/brain/template/the_template", template)

        Then("the runtime configuration for a node assigned to the template should include that content")
        runtimeConf = get(s"conf/brain/runtime-config/$nodeId")
        runtimeConf.getString("the.name") should be ("seven")
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
        val wrote = get(path)

        Then("the returned content is parsed as valid config and contains the same keys and values")
        wrote.getDuration("foo.bar", TimeUnit.MILLISECONDS) should be (100)
        wrote.getString("another.option") should be ("string value")
        wrote.getInt("and.yet.another") should be (42)

        When("A piece of configuration is deleted")
        delete(path) should be (200)
        And("fetched again")
        val deleted = get(path)
        Then("the result is an empty configuration")
        deleted.isEmpty should be (true)
    }

}
