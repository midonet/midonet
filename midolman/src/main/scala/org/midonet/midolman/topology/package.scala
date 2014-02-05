/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.UUID

package object topology {

    type Topology = Map[UUID, Any]

    object Topology {
        def apply() = Map[UUID, Any]()
    }

    implicit class TopologyOps(val topology: Topology) extends AnyVal {
        def device[D](id: UUID) = topology.get(id).asInstanceOf[Option[D]]
    }
}
