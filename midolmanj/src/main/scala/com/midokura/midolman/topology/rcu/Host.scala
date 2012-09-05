/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology.rcu

import java.util.UUID
import collection.immutable
import com.midokura.midonet.cluster.data.TunnelZone

case class Host(id: UUID, datapath: String,
                ports: immutable.Map[UUID, String],
                zones: immutable.Map[UUID, TunnelZone.HostConfig[_, _]])