/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology.rcu

import java.util.UUID
import scala.collection.immutable

import org.midonet.cluster.data.TunnelZone

case class Host(id: UUID, datapath: String,
                ports: immutable.Map[UUID, String],
                zones: immutable.Map[UUID, TunnelZone.HostConfig[_, _]])
