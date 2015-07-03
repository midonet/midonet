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

package org.midonet.cluster.storage

import java.util.UUID
import java.util.concurrent.TimeUnit

import com.google.inject.Guice
import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.test.TestingServer
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException.ConnectionLossException
import org.scalatest.{Matchers, GivenWhenThen, BeforeAndAfterAll, FlatSpec}

import org.midonet.midolman.guice.config.MidolmanConfigModule

class MidonetBackendBufferTest extends FlatSpec with BeforeAndAfterAll
                               with Matchers with GivenWhenThen {

    private final val rootPath = "/midonet/test"
    private var zkServer: TestingServer = _

    val config = ConfigFactory.parseString(
        s"""
           |zookeeper.zookeeper_hosts : "${zkServer.getConnectString}"
           |zookeeper.buffer_size : 524288
           |zookeeper.base_retry : 1s
           |zookeeper.max_retries : 10
           |zookeeper.root_key : "$rootPath"
            """.stripMargin)

    override protected def beforeAll(): Unit = {
        super.beforeAll()
        zkServer = new TestingServer
        zkServer.start()
    }

    override protected def afterAll(): Unit = {
        super.afterAll()
        zkServer.close()
    }

    "Reading 1 MB of data" should "fail with a 512 KB buffer" in {
        Given("A ZooKeeper client with a 512 KB buffer")
        val injector = Guice.createInjector(new MidolmanConfigModule(config),
                                            new MidonetBackendModule(config))

        And("A curator instance connecting to the ZooKeeper server")
        val curator = injector.getInstance(classOf[CuratorFramework])
        curator.start()
        if (!curator.blockUntilConnected(1000, TimeUnit.SECONDS))
            fail("Curator did not connect to the test ZK server")

        And("A set of 1024 children nodes with 1024 character names")
        val zkClient = curator.getZookeeperClient.getZooKeeper
        val path = s"$rootPath/${UUID.randomUUID.toString}"

        ZKPaths.mkdirs(zkClient, path)

        for (index <- 0 to 1024) {
            val childPath = f"$path%s/$index%01024x"
            curator.create().forPath(childPath)
        }

        When("Reading the children")
        val e = intercept[ConnectionLossException] {
            zkClient.getChildren(path, null)
        }

        Then("The operation should fail and lose the connection")
        e.getPath shouldBe path

        curator.close()
    }

    "Reading 256 KB of data" should "succeed with 512 KB buffer" in {
        Given("A ZooKeeper client with a 512 KB buffer")
        val config = ConfigFactory.parseString(
            s"""
               |zookeeper.zookeeper_hosts : "${zkServer.getConnectString}"
               |zookeeper.buffer_size : 524288
               |zookeeper.base_retry : 1s
               |zookeeper.max_retries : 10
               |zookeeper.root_key : "$rootPath"
            """.stripMargin)
        val injector = Guice.createInjector(new MidolmanConfigModule(config),
                                            new MidonetBackendModule(config))

        And("A curator instance connecting to the ZooKeeper server")
        val curator = injector.getInstance(classOf[CuratorFramework])
        curator.start()
        if (!curator.blockUntilConnected(1000, TimeUnit.SECONDS))
            fail("Curator did not connect to the test ZK server")

        And("A set of 1024 children nodes with 1024 character names")
        val zkClient = curator.getZookeeperClient.getZooKeeper
        val path = s"$rootPath/${UUID.randomUUID.toString}"

        ZKPaths.mkdirs(zkClient, path)

        for (index <- 0 to 256) {
            val childPath = f"$path%s/$index%01024x"
            curator.create().forPath(childPath)
        }

        When("Reading the children")
        val children = zkClient.getChildren(path, null)

        Then("The operation should succeed")

        curator.close()
    }

}
