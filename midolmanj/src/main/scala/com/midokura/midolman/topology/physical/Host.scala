/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology.physical

import java.util.UUID
import collection.immutable
import com.midokura.midonet.cluster.data.TunnelZone

/**
 * // TODO: mtoader ! Please explain yourself.
 */
class Host(val id: UUID, val datapath: String,
           val ports: immutable.Map[UUID, String],
           val zones: immutable.Map[UUID, TunnelZone.HostConfig[_, _]]) {
}
