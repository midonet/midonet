/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman

import scala.collection.JavaConversions._

import host.interfaces.InterfaceDescription
import host.scanner.InterfaceScanner
import collection.mutable
import com.midokura.netlink.Callback
import java.util.{List => JList}

class MockInterfaceScanner extends InterfaceScanner {
    private val interfaces = mutable.Map[String, InterfaceDescription]()

    def addInterface(itf: InterfaceDescription): Unit = {
        this.synchronized { interfaces.put(itf.getName, itf) }
    }

    def removeInterface(name: String): Unit = {
        this.synchronized { interfaces.remove(name) }
    }

    override def scanInterfaces(): Array[InterfaceDescription] = {
        this.synchronized { interfaces.values.toArray }
    }

    override def scanInterfaces(
            cb: Callback[JList[InterfaceDescription]]): Unit = {
        this.synchronized { cb.onSuccess(interfaces.values.toList) }
    }

    override def shutDownNow() {}
}