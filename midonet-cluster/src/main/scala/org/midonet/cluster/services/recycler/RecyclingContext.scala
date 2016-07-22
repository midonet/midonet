/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.recycler

import java.util
import java.util.concurrent.{CountDownLatch, ScheduledExecutorService, TimeUnit}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.google.common.util.concurrent.RateLimiter
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.data.Stat

import org.midonet.cluster.RecyclerConfig
import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.MidonetBackend
import org.midonet.util.UnixClock

object RecyclingContext {

    private val ClusterNamespaceId = Seq(MidonetBackend.ClusterNamespaceId.toString)
    private val StepCount = 8

}

/**
  * Contains the context for a recycling operation. An instance of this
  * class contains the state variable for a recycling operation, including
  * the start and finish timestamps, and the NSDB entries that have been
  * recycled (namespaces, objects, state paths).
  */
class RecyclingContext(val config: RecyclerConfig,
                       val curator: CuratorFramework,
                       val store: ZookeeperObjectMapper,
                       val executor: ScheduledExecutorService,
                       val clock: UnixClock,
                       val log: Logger,
                       val interval: Duration) {

    private val start = clock.time
    private var version = 0
    private var timestamp = 0L
    @volatile private var canceled = false
    private val state = new CountDownLatch(1)

    private var hosts: Set[String] = null
    private var namespaces: Set[String] = null

    private val modelObjects = new util.HashMap[Class[_], Set[String]]()
    private val stateObjects = new util.HashMap[(String, Class[_]), Set[String]]()

    private val limiter = RateLimiter.create(config.throttlingRate)

    private var stepIndex = 0

    var totalNamespaces = 0
    var deletedNamespaces = 0
    var skippedNamespaces = 0

    var totalObjects = 0
    var deletedObjects = 0
    var skippedObjects = 0

    /**
      * Cancels the recycling task for the current context.
      */
    def cancel(): Unit = {
        canceled = true
    }

    /**
      * Awaits the completion of the task corresponding to this context.
      */
    def awaitCompletion(duration: Duration): Boolean = {
        state.await(duration.toMillis, TimeUnit.MILLISECONDS)
    }

    /**
      * @return The duration of the current recycling operation.
      */
    def duration: Long = {
        clock.time - start
    }

    /**
      * @return The NSDB version used during recycling.
      */
    def nsdbVersion = version

    @throws[RecyclingException]
    def recycle(): Unit = {
        if (state.getCount == 0) {
            return
        }
        try {
            validate()
            collectHosts()
            collectNamespaces()
            deleteNamespaces()
            collectObjects()
            deleteObjects()
        } finally {
            state.countDown()
        }
    }

    /**
      * Verifies that the current NSDB is recyclable by checking that the root
      * ZOOM node was last modified before the current time minus the current
      * recycling interval. If the NSDB is recyclable the object will write to
      * the root znode to update its last modified timestamp.
      */
    @throws[RecyclingException]
    private def validate(): Unit = {

        val statBefore = new Stat
        log debug s"Verifying if NSDB is recyclable ${step()}"
        getData(store.basePath, statBefore)

        if (start - statBefore.getMtime < interval.toMillis) {
            log debug "Skipping NSDB recycling: already recycled at " +
                      s"${statBefore.getMtime} current time is $start"
            throw new RecyclingCanceledException
        }


        log debug s"Marking NSDB for recycling at $start ${step()}"
        val statAfter = setNode(store.basePath, Recycler.Data,
                                statBefore.getVersion)

        version =
            try  Integer.parseInt(ZKPaths.getNodeFromPath(store.basePath))
            catch {
                case e: NumberFormatException =>
                    log error s"Invalid NSDB version for path ${store.basePath}"
                    throw new RecyclingException("Invalid NSDB version",
                                                 isError = true, inner = null)
            }
        timestamp = statAfter.getMtime
    }

    /**
      * Collects the current hosts from the NSDB, and updates the list of hosts
      * in the current context. The hosts set is used to determine the obsolete
      * namespaces that should be deleted.
      */
    @throws[RecyclingException]
    private def collectHosts(): Unit = {

        log debug s"Collecting current hosts ${step()}"
        hosts = getChildren(store.classPath(classOf[Host])).asScala.toSet

        log debug s"Collected ${hosts.size} hosts"
    }

    /**
      * Collects the current state namespaces from the NSDB, and updates the
      * list of namespaces from the current context.
      */
    @throws[RecyclingException]
    private def collectNamespaces(): Unit = {

        log debug s"Collecting namespaces ${step()}"
        namespaces = getChildren(store.statePath(version)).asScala.toSet

        log debug s"Collected ${namespaces.size} namespaces"
    }

    /**
      * Deletes the orphan namespaces by comparing the collected hosts and
      * namespaces, and deleting all those namespaces that neither have a
      * corresponding host not match the cluster namespace. To delete a
      * namespace, it must have been created before the beginning of the
      * recycling operation.
      */
    @throws[RecyclingException]
    private def deleteNamespaces(): Unit = {

        log debug s"Deleting orphan namespaces ${step()}"

        // Never delete the cluster namespace.
        val orphan = namespaces -- hosts -- RecyclingContext.ClusterNamespaceId

        totalNamespaces = namespaces.size

        log debug s"Found ${orphan.size} orphan namespaces"

        val stat = new Stat()
        for (namespace <- orphan) {

            try {
                log debug s"Verifying namespace $namespace"
                val path = store.stateNamespacePath(namespace, version)
                getData(path, stat)

                if (stat.getCtime < timestamp) {
                    log debug s"Deleting namespace $namespace verified with " +
                              s"timestamp ${stat.getCtime}"
                    deleteWithChildren(path, stat.getVersion)
                    deletedNamespaces += 1
                } else {
                    log debug s"Skipping namespace $namespace with timestamp " +
                              s"${stat.getCtime} newer than $timestamp"
                    skippedNamespaces += 1
                }
            } catch {
                case NonFatal(e) =>
                    log.warn(s"Failed to delete namespace $namespace", e)
                    skippedNamespaces += 1
            }
        }
    }

    /**
      * Collects all objects and their corresponding state paths from the NSDB,
      * and updates their lists from the current context.
      */
    @throws[RecyclingException]
    private def collectObjects(): Unit = {

        log debug s"Collecting objects ${step()}"

        for (clazz <- store.classes) {
            val objects = getChildren(store.classPath(clazz)).asScala.toSet
            modelObjects.put(clazz, objects)

            log debug s"Collected ${objects.size} objects for class " +
                      s"${clazz.getSimpleName}"
        }

        log debug s"Collecting object state ${step()}"

        for (host <- hosts; clazz <- store.classes) {
            val path = store.stateClassPath(host, clazz, version)

            // State paths are created on demand, we must check whether they
            // exist.
            val objects = try zk.getChildren(path, null).asScala.toSet
                          catch { case _: NoNodeException => Set.empty[String] }
            stateObjects.put((host, clazz), objects)

            totalObjects += objects.size

            log debug s"Collected state for ${objects.size} objects for host " +
                      s"$host class ${clazz.getSimpleName}"
        }
    }

    /**
      * Deletes the orphan objects state by comparing the collected objects
      * and state paths, and deleting those that do not correspond to an
      * existing objects. To delete an object state, it must have been created
      * before the beginning of the recyling operation.
      */
    @throws[RecyclingException]
    private def deleteObjects(): Unit = {

        log debug s"Deleting orphan object state for ${hosts.size} hosts ${step()}"

        val stat = new Stat()
        for (entry <- stateObjects.entrySet().asScala;
             id <- entry.getValue
             if !modelObjects.get(entry.getKey._2).contains(id)) {

            val host = entry.getKey._1
            val clazz = entry.getKey._2

            try {
                log debug s"Verifying object ${clazz.getSimpleName}:$id " +
                          s"at host $host"

                val path = store.stateObjectPath(host, clazz, id, version)
                getData(path, stat)

                if (stat.getCtime < timestamp) {
                    log debug "Deleting state for object with timestamp " +
                              s"${stat.getCtime}"
                    delete(path, stat.getVersion)
                    deletedObjects += 1
                } else {
                    log debug "Skipping state for object with timestamp " +
                              s"${stat.getCtime}"
                    skippedObjects += 1
                }
            } catch {
                case NonFatal(e) =>
                    log.warn("Failed to delete state for object " +
                             s"${clazz.getSimpleName}:$id host $host", e)
                    skippedObjects += 1
            }
        }
    }

    /**
      * Verifies whether the current recycling task was canceled.
      */
    @throws[RecyclingException]
    private def verifyCanceled(): Unit = {
        if (canceled) {
            log debug "Recycling canceled"
            throw new RecyclingCanceledException
        }
    }

    /**
      * Throttles an NSDB read or write operation and verifies before and after
      * whether the recycling task was canceled.
      */
    @throws[RecyclingException]
    private def throttle(): Unit = {
        verifyCanceled()
        limiter.acquire()
        verifyCanceled()
    }

    @throws[RecyclingException]
    private def getData(path: String, stat: Stat): Array[Byte] = {
        throttle()
        try zk.getData(path, null, stat)
        catch {
            case NonFatal(e) => throw new RecyclingStorageException(e)
        }
    }

    @throws[RecyclingException]
    private def setNode(path: String, data: Array[Byte], version: Int): Stat = {
        throttle()
        try zk.setData(path, data, version)
        catch {
            case NonFatal(e) => throw new RecyclingStorageException(e)
        }
    }

    @throws[RecyclingException]
    private def getChildren(path: String): util.List[String] = {
        throttle()
        try zk.getChildren(path, null)
        catch {
            case NonFatal(e) => throw new RecyclingStorageException(e)
        }
    }

    @throws[RecyclingException]
    private def delete(path: String, version: Int): Unit = {
        throttle()
        try zk.delete(path, version)
        catch {
            case NonFatal(e) => throw new RecyclingStorageException(e)
        }
    }

    @throws[RecyclingException]
    private def deleteWithChildren(path: String, version: Int): Unit = {
        throttle()
        try {
            ZKPaths.deleteChildren(zk, path, false)
            zk.delete(path, version)
        } catch {
            case NonFatal(e) => throw new RecyclingStorageException(e)
        }
    }

    /**
      * Returns the underlying [[ZooKeeper]] client.
      */
    private def zk: ZooKeeper = curator.getZookeeperClient.getZooKeeper

    /**
      * Returns the current recycling step as string.
      */
    private def step(): String = {
        stepIndex += 1
        s"(step $stepIndex of ${RecyclingContext.StepCount})"
    }
}
