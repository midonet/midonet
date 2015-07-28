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

package org.midonet.cluster.test.util

import com.typesafe.config.ConfigFactory
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, Suite}

import org.midonet.conf.MidoTestConfigurator

/**
 * Created by galo on 28/07/2015.
 */
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
