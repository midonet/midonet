/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import java.util.{List => JList}
import java.util.UUID

import com.midokura.midolman.rules.Rule


class Chain(val id: UUID, val rules: JList[Rule],
            val jumpTargets: Map[UUID, Chain], val name: String) {

    override def hashCode = id.hashCode()

    override def equals(other: Any) = other match {
        case that: Chain =>
            (that canEqual this) && (this.id == that.id) &&
            (this.rules == that.rules) && (this.jumpTargets == that.jumpTargets)
        case _ =>
            false
    }

    def canEqual(other: Any) = other.isInstanceOf[Chain]

    def getJumpTarget(id: UUID): Chain = {
        jumpTargets.get(id) match {
            case Some(chain) => chain
            case None => null
        }
    }

    def getChainName(): String = name

    def getRules() = rules

    def getID() = id
}
