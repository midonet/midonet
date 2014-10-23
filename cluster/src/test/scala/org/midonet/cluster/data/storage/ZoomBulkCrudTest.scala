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
package org.midonet.cluster.data.storage

import org.apache.curator.framework.CuratorFrameworkFactory
import org.junit.runner.RunWith
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.scalatest.junit.JUnitRunner


/**
 * Tests for bulk CRUD operations with ZOOM-based Storage Service.
 */
@RunWith(classOf[JUnitRunner])
class ZoomBulkCrudTest extends StorageBulkCrudTest
                       with ZoomStorageTester {
    protected val ZK_PORT = 2181
    protected val ZK_CONNECT_STRING = "127.0.0.1:" + ZK_PORT

    /**
     * Sets up a new CuratorFramework client against a local ZooKeeper setup.
     */
    override protected def setUpCurator(): Unit = {
        curator = CuratorFrameworkFactory.newClient(ZK_CONNECT_STRING,
                                                    retryPolicy)
    }

    override def registerClass(c: Class[_]): Unit = zoom.registerClass(c)

    override def isRegistered(c: Class[_]): Boolean = zoom.isRegistered(c)

    override def declareBinding(leftClass: Class[_], leftFieldName: String,
                                onDeleteLeft: DeleteAction,
                                rightClass: Class[_], rightFieldName: String,
                                onDeleteRight: DeleteAction): Unit =
        zoom.declareBinding(leftClass, leftFieldName, onDeleteLeft,
                            rightClass, rightFieldName, onDeleteRight)

    /** This method must be called after all calls to registerClass() and
      * declareBinding() have been made, but before any calls to data-related
      * methods such as CRUD operations and subscribe().
      */
    override def build(): Unit = zoom.build()

    override def isBuilt: Boolean = zoom.isBuilt
}
