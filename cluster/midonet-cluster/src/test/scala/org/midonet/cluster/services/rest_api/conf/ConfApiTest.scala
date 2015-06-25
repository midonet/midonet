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

package org.midonet.cluster.services.rest_api.conf

import java.util.UUID
import java.util.concurrent.TimeUnit

import com.typesafe.config.{ConfigException, ConfigFactory}
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.apache.http.client.fluent.Request
import org.apache.http.entity.ContentType
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.{ClusterConfig, ClusterNode}
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator, MidoTestConfigurator}

trait ZookeeperTestSuite extends BeforeAndAfterAll with BeforeAndAfter { this: Suite =>
    var zkServer: TestingServer = _
    var zkClient: CuratorFramework = _

    val ZK_PORT: Int = 10000 + (Math.random() * 50000).toInt
    val ZK_ROOT = "/test"

    protected def config = MidoTestConfigurator.forClusters(ConfigFactory.parseString(
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
            zkClient.create().forPath(ZK_ROOT)
        } catch {
            case e: Exception => // ignore
                println(e.getMessage)
                e.printStackTrace(System.out)
        }
    }
}


@RunWith(classOf[JUnitRunner])
class ConfApiTest extends FeatureSpecLike
                            with Matchers
                            with BeforeAndAfterAll
                            with BeforeAndAfter
                            with GivenWhenThen
                            with ZookeeperTestSuite {

    HostIdGenerator.useTemporaryHostId()

    var confMinion: ConfMinion = _

    val HTTP_PORT: Int = 10000 + (Math.random() * 50000).toInt

    private val confStr =
        s"""
          |cluster.conf_api.enabled : true
          |cluster.conf_api.http_port : $HTTP_PORT
        """.stripMargin

    override def config = ConfigFactory.parseString(confStr).withFallback(super.config)

    before {
        clearZookeeper()
        MidoNodeConfigurator(config).deployBundledConfig()
    }

    override def beforeAll(): Unit = {
        super.beforeAll()
        val ctx = ClusterNode.Context(HostIdGenerator.getHostId, embed = true)
        confMinion = new ConfMinion(ctx, new ClusterConfig(config))
        confMinion.startAsync().awaitRunning()

    }

    override def afterAll(): Unit = {
        super.afterAll()
        confMinion.stopAsync().awaitTerminated()
    }

    private def url(path: String) = s"http://127.0.0.1:$HTTP_PORT/$path"

    private def get(path: String) = ConfigFactory.parseString(
        Request.Get(url(path)).execute().returnContent().asString())

    private def post(path: String, content: String) = {
        Request.Post(url(path)).bodyString(content, ContentType.TEXT_PLAIN)
            .execute().discardContent()
    }

    private def delete(path: String) =
        Request.Delete(url(path)).execute().returnResponse().getStatusLine.getStatusCode

    scenario("reads, writes and deletes templates") {
        testWritableSource("conf/template/new_template")
    }

    scenario("reads, writes and deletes per node cluster configuration") {
        testWritableSource("conf/node/" + UUID.randomUUID())
    }

    scenario("reads schemas") {
        When("When a GET is done on the schema URL for a known node type")
        val schema = get("conf/schema")

        schema.isEmpty should be (false)
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
        mappings.getString(nodeId) should be("the_template")

        var runtimeConf = get(s"conf/runtime-config/$nodeId")
        intercept[ConfigException.Missing] {
            runtimeConf.getString("the.name") should be ("seven")
        }

        When("New configuration content is posted to a template")
        post("conf/template/the_template", template)

        Then("the runtime configuration for a node assigned to the template should include that content")
        runtimeConf = get(s"conf/runtime-config/$nodeId")
        runtimeConf.getString("the.name") should be ("seven")
    }

    scenario("lists templates") {
        val seven = s"""the.name : "seven" """
        val vandelay = s"""art.vandelay : "architect" """

        When("New two templates are created for the first time")
        val origSize = get("conf/template-list").getStringList("templates").size

        post("conf/template/seven", seven)
        post("conf/template/vandelay", vandelay)

        Then("the list of templates should contain the new templates")
        val templates = get("conf/template-list").getStringList("templates")
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
