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

import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.{CreateMode, KeeperException}

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.cluster.data.storage.StateTableEncoder.PersistentVersion

trait DirectoryStateTable[K, V]
    extends StateTable[K, V] with StateTableEncoder[K, V] {

    protected def directory: Directory
    protected def nullValue: V

    /**
      * @see [[StateTable.addPersistent()]]
      */
    @throws[StateAccessException]
    @inline
    override def addPersistent(key: K, value: V): Unit = {
        tryZk {
            directory.add(encodePersistentPath(key, value), null,
                          CreateMode.PERSISTENT)
        }
    }

    /**
      * @see [[StateTable.removePersistent()]]
      */
    @throws[StateAccessException]
    override def removePersistent(key: K, value: V): Boolean = {
        val path = encodePersistentPath(key, value)
        if (directory.exists(path)) {
            try {
                directory.delete(path)
                true
            } catch {
                case e: NoNodeException => false
                case e: KeeperException => throw new StateAccessException(e)
                case e: InterruptedException => throw new StateAccessException(e)
            }
        } else false
    }

    /**
      * @see [[StateTable.containsRemote()]]
      */
    @throws[StateAccessException]
    @inline
    override def containsRemote(key: K): Boolean = {
        getEntries.get(key) ne null
    }

    /**
      * @see [[StateTable.containsRemote()]]
      */
    @throws[StateAccessException]
    @inline
    override def containsRemote(key: K, value: V): Boolean = {
        hasPersistentEntry(key, value) || hasLearnedEntry(key, value)
    }

    /**
      * @see [[StateTable.containsPersistent()]]
      */
    @throws[StateAccessException]
    @inline
    override def containsPersistent(key: K, value: V): Boolean = {
        hasPersistentEntry(key, value)
    }

    /**
      * Gets the remote value for the specified key.
      */
    @inline
    def getRemote(key: K): V = {
        val entry = getEntries.get(key)
        if (entry ne null) entry._1 else nullValue
    }

    /**
      * @see [[StateTable.getRemoteByValue()]]
      */
    @inline
    override def getRemoteByValue(value: V): Set[K] = {
        val iterator = tryZk { directory.getChildren("/", null).iterator() }
        val set = new util.HashSet[K]()
        while (iterator.hasNext) {
            decodePath(iterator.next()) match {
                case (key, v, _) if v == value => set.add(key)
                case _ =>
            }
        }
        set.asScala.toSet
    }

    /**
      * @see [[StateTable.remoteSnapshot]]
      */
    @inline
    override def remoteSnapshot: Map[K, V] = {
        getEntries.asScala.mapValues(_._1).toMap
    }

    private def hasPersistentEntry(key: K, value: V): Boolean = {
        directory.exists(encodePersistentPath(key, value))
    }

    private def hasLearnedEntry(key: K, value: V): Boolean = {
        getEntries get key match {
            case null => false
            case (_, PersistentVersion) => false
            case (v, _) if v == value => true
            case _ => false
        }
    }

    protected def tryZk[T](f: => T): T = {
        try f catch {
            case e: KeeperException      => throw new StateAccessException(e)
            case e: InterruptedException => throw new StateAccessException(e)
        }
    }

    protected def getEntries: util.Map[K, (V, Int)] = {
        val iterator = tryZk { directory.getChildren("/", null).iterator() }
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
        map
    }

}
