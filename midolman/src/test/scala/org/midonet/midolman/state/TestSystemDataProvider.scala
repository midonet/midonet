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

package org.midonet.midolman.state

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import org.scalatest.junit.JUnitRunner
import org.scalatest._
import org.scalatest.Matchers._
import org.scalatest.concurrent.Eventually

import org.apache.zookeeper.{CreateMode, KeeperException}
import org.junit.runner.RunWith

import org.slf4j.LoggerFactory

import org.midonet.midolman.Setup
import org.midonet.midolman.version.VersionComparator
import org.midonet.midolman.version.DataWriteVersion.{CURRENT => CURRENT_VERSION}

/**
 * Test for zookeeper system data provider
  */
@RunWith(classOf[JUnitRunner])
class TestSystemDataProvider extends FeatureSpec with Eventually {
    private val log = LoggerFactory.getLogger(classOf[TestSystemDataProvider])

    val basePath = "/midolman"
    val pathMgr = new PathBuilder(basePath)

    scenario("async reading of write version") {
        val dir = new MockDirectory()
        dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
        Setup.ensureZkDirectoryStructureExists(dir, basePath)

        val zk = new ZkManager(dir, basePath)
        val dataProvider = new ZkSystemDataProvider(zk, pathMgr,
                                                    new VersionComparator())
        val dataProvider2 = new ZkSystemDataProvider(zk, pathMgr,
                                                     new VersionComparator())


        val NEW_VERSION = "10.0"
        dataProvider.getWriteVersion() should be (CURRENT_VERSION)
        dataProvider2.getWriteVersion() should be (CURRENT_VERSION)

        dataProvider2.setWriteVersion(NEW_VERSION)

        dataProvider.getWriteVersion() should be (NEW_VERSION)
        dataProvider2.getWriteVersion() should be (NEW_VERSION)
    }

    scenario("One node cannot read update for a couple of retries") {
        val errorGets = new AtomicInteger(0)
        val dir = new MockDirectory() {
            override def asyncGet(relativePath: String,
                                  dataCb: DirectoryCallback[Array[Byte]],
                                  watcher: Directory.TypedWatcher) {
                log.info(s"calling async get ${errorGets}")
                if (errorGets.decrementAndGet() > 0) {
                    dataCb.onError(new KeeperException.ConnectionLossException)
                } else {
                    super.asyncGet(relativePath, dataCb, watcher)
                }
            }
        }
        dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
        Setup.ensureZkDirectoryStructureExists(dir, basePath)

        val zk = new ZkManager(dir, basePath)
        val dataProvider = new ZkSystemDataProvider(zk, pathMgr,
                                                    new VersionComparator())

        dataProvider.getWriteVersion() should be (CURRENT_VERSION)

        val NEW_VERSION = "10.0"
        errorGets.set(5)
        zk.update(pathMgr.getWriteVersionPath(), NEW_VERSION.getBytes());

        eventually { dataProvider.getWriteVersion() should be (NEW_VERSION) }
        errorGets.get should be (0)
    }

    scenario("One node cannot read update at all") {
        val erroringReads = new AtomicBoolean(false)
        val dir = new MockDirectory() {
            override def get(path: String, watcher: Runnable): Array[Byte] = {
                if (erroringReads.get) {
                    throw new KeeperException.ConnectionLossException
                } else {
                    return super.get(path, watcher)
                }
            }

            override def asyncGet(relativePath: String,
                                  dataCb: DirectoryCallback[Array[Byte]],
                                  watcher: Directory.TypedWatcher): Unit = {
                if (erroringReads.get) {
                    dataCb.onError(new KeeperException.ConnectionLossException)
                } else {
                    super.asyncGet(relativePath, dataCb, watcher)
                }
            }
        }
        dir.add(pathMgr.getBasePath, null, CreateMode.PERSISTENT)
        Setup.ensureZkDirectoryStructureExists(dir, basePath)

        val zk = new ZkManager(dir, basePath)
        val dataProvider = new ZkSystemDataProvider(zk, pathMgr,
                                                    new VersionComparator())
        dataProvider.getWriteVersion() should be (CURRENT_VERSION)

        erroringReads.set(true)
        val NEW_VERSION = "10.0"
        zk.update(pathMgr.getWriteVersionPath(), NEW_VERSION.getBytes());

        intercept[StateAccessException] {
            while (true) {
                val foo = dataProvider.getWriteVersion()
                log.info(s"foo is ${foo}")
                foo should be (CURRENT_VERSION)
            }
        }
        erroringReads.set(false)
        dataProvider.getWriteVersion() should be (NEW_VERSION)
    }
}
