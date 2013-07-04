/* Copyright 2013 Midokura Europe SARL */
package org.midonet.midolman.state

import _root_.org.apache.zookeeper.CreateMode
import _root_.org.midonet.packets.{MAC, IPv4Addr}
import java.util.HashMap
import java.util.Map
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException
import org.midonet.packets.IPv4Addr
import org.midonet.packets.MAC
import collection.JavaConversions._

class Ip4ToMacReplicatedMap(dir: Directory)
    extends ReplicatedMap[IPv4Addr, MAC](dir)
{
    protected def encodeKey(key: IPv4Addr): String = key.toString
    protected def decodeKey(str: String): IPv4Addr = IPv4Addr.fromString(str)
    protected def encodeValue(value: MAC): String = value.toString
    protected def decodeValue(str: String): MAC = MAC.fromString(str)
}

object Ip4ToMacReplicatedMap {

    def getAsMap(dir: Directory): Map[IPv4Addr, MAC] = convertException {
        val paths: Iterable[java.lang.String] = dir.getChildren("/", null)
        val m: Map[IPv4Addr, MAC] = new HashMap[IPv4Addr, MAC]
        for (path <- paths) {
            val parts: Array[String] =
                ReplicatedMap.getKeyValueVersion(path)
            m.put(IPv4Addr.fromString(parts(0)), MAC.fromString(parts(1)))
        }
        m
    }

    def hasPersistentEntry(dir: Directory, key: IPv4Addr, value: MAC): Boolean
        = convertException(dir.has(encodePersistentPath(key, value)))

    def addPersistentEntry(dir: Directory, key: IPv4Addr, value: MAC)
        = convertException(dir.add(
            encodePersistentPath(key, value), null, CreateMode.PERSISTENT))

    def deletePersistentEntry(dir: Directory, key: IPv4Addr, value: MAC)
        = convertException(dir.delete(encodePersistentPath(key, value)))

    def encodePersistentPath(k :IPv4Addr, v :MAC) =
        ReplicatedMap.encodeFullPath(k.toString, v.toString, 1)

    def convertException[T](f :T) :T = {
        try { f } catch {
            case e: KeeperException      => throw new StateAccessException(e)
            case e: InterruptedException => throw new StateAccessException(e)
        }
    }
}

