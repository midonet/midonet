/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import java.util.UUID
import com.midokura.midolman.rules.Rule

class Chain(val id: UUID, val rules: List[Rule],
            val jumpTargets: Map[UUID, Chain]) {

    override def hashCode = id.hashCode()

    override def equals(other: Any) = other match {
        case that: Chain =>
            (that canEqual this) &&
                (this.id == that.id) && (this.rules == that.rules) && (this.jumpTargets == that.jumpTargets)
        case _ =>
            false
    }

    def canEqual(other: Any) = other.isInstanceOf[Chain]
}
