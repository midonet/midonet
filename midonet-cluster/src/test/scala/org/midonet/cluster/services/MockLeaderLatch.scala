package org.midonet.cluster.services

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable.ListBuffer

import com.google.common.annotations.VisibleForTesting

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.LeaderLatch.CloseMode
import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}

import org.midonet.cluster.conf.ClusterConfig

class MockLeaderLatchProvider(backend: MidonetBackend,
                              config: ClusterConfig)
    extends LeaderLatchProvider(backend, config){
    override def buildLatch(path: String): LeaderLatch = {
        new MockLeaderLatch(backend.curator, path)
    }
}

/** This class lets the test control notifications about acquisition
  * and lost of a latch.  The typical use case will create a
  * LeaderLatchProvider that builds one of these instances, then inject
  * the provider to the relevant class such that it starts the latch
  * normally.  The test can then use `isLeader` and `notLeader` to notify
  * for both events.
  *
  * Note that we do NOT use the custom executor provided in addListener.
  * This is used in our code (e.g.: ContainerService) to serialize
  * notifications, but it's not really a need in tests.
  */
class MockLeaderLatch(curator: CuratorFramework, path: String)
    extends LeaderLatch(curator, path) {

    @VisibleForTesting // obviously, exposed to allow inspection
    val listeners = new ListBuffer[LeaderLatchListener]()

    private val _isStarted = new AtomicBoolean(false)

    @VisibleForTesting // obviously, exposed to allow inspection
    var closeMode: CloseMode = _

    def isStarted = _isStarted.get

    override def close(): Unit = {
        if (!_isStarted.compareAndSet(true, false)) {
            throw new RuntimeException("Latch closed before started")
        }
        closeMode = CloseMode.SILENT
    }
    override def close(cm: CloseMode): Unit = {
        if (!_isStarted.compareAndSet(true, false)) {
            throw new RuntimeException("Latch closed before started")
        }
        closeMode = cm
    }
    override def start(): Unit = {
        if (!_isStarted.compareAndSet(false, true)) {
            throw new RuntimeException("Latch already started")
        }
        closeMode = null
    }

    override def addListener(l: LeaderLatchListener)
    : Unit = listeners += l

    override def addListener(l: LeaderLatchListener, ec: Executor)
    : Unit = listeners += l

    override def removeListener(l: LeaderLatchListener)
    : Unit = listeners -= l

    /** Trigger the notification that signals that the holder of this
      * instance has grabbed the latch.
      *
      */
    def isLeader() = listeners foreach { _.isLeader() }

    /** Trigger the notification that signals that the holder of this
      * instance has lost the latch.
      */
    def notLeader() = listeners foreach { _.notLeader() }
}
