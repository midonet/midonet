/* Copyright 2011 Midokura Inc. */
package org.midonet.midolman.state

import java.util.HashMap
import java.util.Map
import java.util.UUID
import org.apache.zookeeper.CreateMode
import org.midonet.packets.MAC
import scala.collection.JavaConversions._

class MacPortMap(dir: Directory) extends ReplicatedMap[MAC, UUID](dir) {
    protected def encodeKey(key: MAC): String = key.toString
    protected def decodeKey(str: String): MAC = MAC.fromString(str)
    protected def encodeValue(value: UUID): String =value.toString
    protected def decodeValue(str: String): UUID = UUID.fromString(str)
}

object MacPortMap {

    def getAsMap(dir: Directory): Map[MAC, UUID] =
        getAsMapBase(dir, (mac: String, port: String, version: String) =>
            (MAC.fromString(mac), UUID.fromString(port)))

    def getAsMapWithVersion(dir: Directory): Map[MAC, (UUID, Int)] =
        getAsMapBase(dir, (mac: String, port: String, version: String) =>
            (MAC.fromString(mac), (UUID.fromString(port), version.toInt)))

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

    def hasPersistentEntry(dir: Directory, key: MAC, value: UUID): Boolean
    = ZKExceptions.adapt(dir.has(encodePersistentPath(key, value)))

    def addPersistentEntry(dir: Directory, key: MAC, value: UUID)
    = ZKExceptions.adapt(
        dir.add(encodePersistentPath(key, value), null, CreateMode.PERSISTENT))

    def deleteEntry(dir: Directory, key: MAC, mac: UUID) = {
        getAsMapWithVersion(dir).get(key) match {
            case (m,ver) => if (m.equals(mac))
                dir.delete(encodePath(key, mac, ver))
            case _ => ()
        }
    }

    def encodePersistentPath(k :MAC, v :UUID) =
        encodePath(k, v, 1)

    def encodePath(k :MAC, v :UUID, ver: Int) =
        ReplicatedMap.encodeFullPath(k.toString, v.toString, ver)

}
