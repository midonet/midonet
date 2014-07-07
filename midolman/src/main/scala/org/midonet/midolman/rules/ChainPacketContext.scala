// Copyright 2013 Midokura Inc.

package org.midonet.midolman.rules

import java.util.{Set => JSet}
import java.util.UUID

import org.midonet.util.functors.Callback0
import org.midonet.sdn.flows.FlowTagger
import FlowTagger.FlowTag
;

trait ChainPacketContext {
    def inPortId: UUID
    def outPortId: UUID
    def portGroups: JSet[UUID]
    def isConnTracked: Boolean
    def isForwardFlow: Boolean
    def flowCookie: Option[Int]
    def parentCookie: Option[Int]

    def addFlowTag(tag: FlowTag)
    def addFlowRemovedCallback(cb: Callback0)
}
