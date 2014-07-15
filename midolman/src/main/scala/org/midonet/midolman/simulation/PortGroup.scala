/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import org.midonet.sdn.flows.FlowTagger

class PortGroup(val id: UUID, val name: String, val stateful: Boolean,
                val members: Set[UUID]) {
    val deviceTag = FlowTagger.tagForDevice(id)
}
