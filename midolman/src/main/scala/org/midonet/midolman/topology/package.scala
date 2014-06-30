/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

package object topology {

    type Topology = ConcurrentHashMap[UUID, AnyRef]

    object Topology {
        def apply() = new ConcurrentHashMap[UUID, AnyRef]()
    }

    implicit class TopologyOps(val topology: Topology) extends AnyVal {
        def device[D <: AnyRef](id: UUID) = topology.get(id).asInstanceOf[D]
    }
}
