/* Copyright 2013 Midokura Europe SARL */
package org.midonet.midolman.state

import java.util.Map
import org.apache.zookeeper.CreateMode
import org.midonet.packets.IPv4Addr
import org.midonet.packets.MAC
import collection.immutable._
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

    def getAsMap(dir: Directory): Map[IPv4Addr, MAC] =
        getAsMapBase(dir, (ip: String, mac: String, version: String) =>
            (IPv4Addr.fromString(ip), MAC.fromString(mac)))

    def getAsMapWithVersion(dir: Directory): Map[IPv4Addr, (MAC, Int)] =
        getAsMapBase(dir, (ip: String, mac: String, version: String) =>
            (IPv4Addr.fromString(ip), (MAC.fromString(mac), version.toInt)))

    def getAsMapBase[K,V](dir: Directory,
            mapEntryConvert: (String, String, String) => (K,V))
            :collection.immutable.Map[K,V] =
        ZKExceptions.adapt {
            def makeMapEntry(path:String) = {
                val parts: Array[String] =
                    ReplicatedMap.getKeyValueVersion(path)
                mapEntryConvert(parts(0), parts(1), parts(2))
            }
            dir.getChildren("/", null).map(makeMapEntry).toMap
        }

    def hasPersistentEntry(dir: Directory, key: IPv4Addr, value: MAC): Boolean
        = ZKExceptions.adapt(dir.has(encodePersistentPath(key, value)))

    def addPersistentEntry(dir: Directory, key: IPv4Addr, value: MAC)
        = ZKExceptions.adapt(dir.add(
            encodePersistentPath(key, value), null, CreateMode.PERSISTENT))

    def deleteEntry(dir: Directory, key: IPv4Addr, mac: MAC) = {
        getAsMapWithVersion(dir).get(key) match {
            case (m,ver) => if (m.equals(mac))
                               dir.delete(encodePath(key, mac, ver))
            case _ => ()
        }
    }

    def encodePersistentPath(k :IPv4Addr, v :MAC) =
        encodePath(k, v, 1)

    def encodePath(k :IPv4Addr, v :MAC, ver: Int) =
        ReplicatedMap.encodeFullPath(k.toString, v.toString, ver)

}

