// Copyright 2012 Midokura Inc.

package com.midokura.midolman.simulation

import akka.dispatch.ExecutionContext


abstract class ProcessResult()

// TODO(jlm): Should we have DropResult include how wide a drop rule to use?
//            Then we could fold in NotIPv4Result
case class DropResult() extends ProcessResult
case class NotIPv4Result() extends ProcessResult
case class ConsumedResult() extends ProcessResult
case class ForwardResult(portmatch: PortMatch) extends ProcessResult


trait Device {
    def process(context: PacketContext, ec: ExecutionContext): ProcessResult
}
