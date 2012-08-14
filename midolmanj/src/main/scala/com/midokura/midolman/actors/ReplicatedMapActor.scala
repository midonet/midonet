// Copyright 2012 Midokura Inc.

package com.midokura.midolman.actors

import akka.actor.ActorSystem
import akka.event.Logging
import scala.collection.JavaConversions._
import scala.collection.Map

import org.apache.zookeeper.KeeperException

import com.midokura.midolman.state.Directory
import com.midokura.util.functors.Callback1


abstract class ReplicatedMap[K, V](zkDir: Directory) {
    private var running = false
    private var myWatcher = new DirectoryWatcher
    private val log = Logging(ActorSystem("XXX"), getClass)

    def start() {
        if (!running) {
            running = true
            myWatcher.run
        }
    }

    def stop() {
        running = false
        // XXX: Send a 'stop' message?
    }

    // encodeKey and encodeValue must not produce strings containing ','
    protected def encodeKey(key: K): String             // abstract
    protected def decodeKey(str: String): K             // abstract
    protected def encodeValue(value: V): String         // abstract
    protected def decodeValue(str: String): V           // abstract


    private var newMapCallback = new Callback1[Map[K,V]] {
        def call(m: Map[K, V]) {
            log.error("newMapCallback called without a callback registered")
        }
    }

    private class DirectoryWatcher extends Runnable {
        def run() {
            if (!running)
                return
            try {
                val paths = zkDir.getChildren("/", this)
                val newMap = paths map ((x: String) =>
                                 decodePath(x).key -> decodePath(x).value)

                newMapCallback.call(newMap.toMap)
            } catch {
                case e: KeeperException =>
                            log.error("DirectoryWatcher.run", e)
                            throw new RuntimeException(e)
            }
        }
    }

    private class Path(val key: K, val value: V, val version: Int)

    private def decodePath(str: String): Path = {
        val strippedStr = (if (str.startsWith("/"))
                               str.substring(1)
                           else
                               str)
        val parts = strippedStr.split(",")
        return new Path(decodeKey(parts(0)), decodeValue(parts(1)),
                        parts(2).toInt)
    }

}
