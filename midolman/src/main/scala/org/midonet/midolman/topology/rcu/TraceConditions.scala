/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology.rcu

import collection.immutable

import org.midonet.midolman.rules.Condition

case class TraceConditions(conditions: immutable.Seq[Condition])
