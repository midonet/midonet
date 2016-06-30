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

package org.midonet.cluster.data.storage

import java.util

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{CreateMode, KeeperException}

import org.midonet.cluster.backend.{Directory, DirectoryCallback}
import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.cluster.data.storage.StateTableEncoder.PersistentVersion
import org.midonet.util.concurrent.CallingThreadExecutionContext

trait DirectoryStateTable[K, V]
    extends StateTable[K, V] with StateTableEncoder[K, V] {

    protected def directory: Directory
    protected def nullValue: V

    /**
      * @see [[StateTable.addPersistent()]]
      */
    @inline
    override def addPersistent(key: K, value: V): Future[Unit] = {
        val promise = Promise[Unit]()
        val callback = new DirectoryCallback[String] {
            override def onSuccess(name: String, stat: Stat,
                                   context: Object): Unit =
                promise.trySuccess(())
            override def onError(e: KeeperException): Unit =
                promise.tryFailure(wrapException(e))
        }
        directory.asyncAdd(encodePersistentPath(key, value), null,
                           CreateMode.PERSISTENT, callback, null)
        promise.future
    }

    /**
      * @see [[StateTable.removePersistent()]]
      */
    @throws[StateAccessException]
    override def removePersistent(key: K, value: V): Future[Boolean] = {
        val path = encodePersistentPath(key, value)
        val promise = Promise[Boolean]()
        val deleteCallback = new DirectoryCallback[Void] {
            override def onSuccess(data: Void, stat: Stat, context: Object): Unit =
                promise.trySuccess(true)
            override def onError(e: KeeperException): Unit =
                promise.tryFailure(wrapException(e))
        }
        val existsCallback = new DirectoryCallback[java.lang.Boolean] {
            override def onSuccess(exists: java.lang.Boolean, stat: Stat,
                                   context: Object): Unit = {
                if (exists) directory.asyncDelete(path, stat.getVersion,
                                                  deleteCallback, context)
                else promise.trySuccess(false)
            }
            override def onError(e: KeeperException): Unit =
                promise.tryFailure(wrapException(e))
        }
        directory.asyncExists(path, existsCallback, null)
        promise.future
    }

    /**
      * @see [[StateTable.containsRemote()]]
      */
    @throws[StateAccessException]
    @inline
    override def containsRemote(key: K): Future[Boolean] = {
        getEntries.map(_.get(key) ne null)(CallingThreadExecutionContext)
    }

    /**
      * @see [[StateTable.containsRemote()]]
      */
    @throws[StateAccessException]
    @inline
    override def containsRemote(key: K, value: V): Future[Boolean] = {
        hasPersistentEntry(key, value).flatMap { result =>
            if (!result) hasLearnedEntry(key, value)
            else Future.successful(result)
        } (CallingThreadExecutionContext)
    }

    /**
      * @see [[StateTable.containsPersistent()]]
      */
    @throws[StateAccessException]
    @inline
    override def containsPersistent(key: K, value: V): Future[Boolean] = {
        hasPersistentEntry(key, value)
    }

    /**
      * Gets the remote value for the specified key.
      */
    @inline
    def getRemote(key: K): Future[V] = {
        getEntries.map { entries =>
            val entry = entries.get(key)
            if (entry ne null) entry._1 else nullValue
        } (CallingThreadExecutionContext)
    }

    /**
      * @see [[StateTable.getRemoteByValue()]]
      */
    @inline
    override def getRemoteByValue(value: V): Future[Set[K]] = {
        val promise = Promise[Set[K]]()
        val callback = new DirectoryCallback[util.Collection[String]] {
            override def onSuccess(children: util.Collection[String],
                                   stat: Stat, context: Object): Unit = {
                val iterator = children.iterator()
                val set = new util.HashSet[K]()
                while (iterator.hasNext) {
                    decodePath(iterator.next()) match {
                        case (key, v, _) if v == value => set.add(key)
                        case _ =>
                    }
                }
                promise.trySuccess(set.asScala.toSet)
            }
            override def onError(e: KeeperException): Unit =
                promise.tryFailure(wrapException(e))
        }
        directory.asyncGetChildren("", callback, null, null)
        promise.future
    }

    /**
      * @see [[StateTable.remoteSnapshot]]
      */
    @inline
    override def remoteSnapshot: Future[Map[K, V]] = {
        getEntries.map(_.asScala.mapValues(_._1).toMap)(CallingThreadExecutionContext)
    }

    private def hasPersistentEntry(key: K, value: V): Future[Boolean] = {
        val promise = Promise[Boolean]()
        val callback = new DirectoryCallback[java.lang.Boolean] {
            override def onSuccess(exists: java.lang.Boolean, stat: Stat,
                                   context: Object): Unit =
                promise.trySuccess(exists)
            override def onError(e: KeeperException): Unit =
                promise.tryFailure(wrapException(e))
        }
        directory.asyncExists(encodePersistentPath(key, value), callback, null)
        promise.future
    }

    private def hasLearnedEntry(key: K, value: V): Future[Boolean] = {
        getEntries.map {
            _.get(key) match {
                case null => false
                case (_, PersistentVersion) => false
                case (v, _) if v == value => true
                case _ => false
            }
        } (CallingThreadExecutionContext)
    }

    protected def wrapException(e: Exception): StateAccessException = {
        e match {
            case e: StateAccessException => e
            case NonFatal(t) => new StateAccessException(t)
        }
    }

    /**
      * @return A future that completes with the set of entries for this state
      *         table. The map returned by the completed future is not
      *         synchronized and continuations of this future must execute on
      *         the calling thread.
      */
    protected def getEntries: Future[util.Map[K, (V, Int)]] = {
        val promise = Promise[util.Map[K, (V, Int)]]()
        val callback = new DirectoryCallback[util.Collection[String]] {
            override def onSuccess(children: util.Collection[String],
                                   stat: Stat, context: Object): Unit = {
                val iterator = children.iterator()
                val map = new util.HashMap[K, (V, Int)]()
                while (iterator.hasNext) {
                    decodePath(iterator.next()) match {
                        case (key, value, version) =>
                            map get key match {
                                case null =>
                                    map.put(key, (value, version))
                                case (v, ver) if version > ver &&
                                                 version != PersistentVersion =>
                                    map.put(key, (value, version))
                                case (v, ver) if ver == PersistentVersion &&
                                                 version != PersistentVersion =>
                                    map.put(key, (value, version))
                                case _ => // Ignore: multiple persistent entries.
                            }
                        case _ => // Ignore: entries that cannot be read.
                    }
                }
                promise.trySuccess(map)
            }
            override def onError(e: KeeperException): Unit =
                promise.tryFailure(wrapException(e))
        }
        directory.asyncGetChildren("", callback, null, null)
        promise.future
    }

}
