/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology.rcu

import java.util.UUID
import collection.immutable

case class PortSet(id: UUID, hosts: immutable.Set[UUID], localPorts: immutable.Set[UUID])