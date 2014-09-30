/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
                       with ZoomStorageServiceTester {
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
