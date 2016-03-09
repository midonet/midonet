package org.midonet.midolman.state

import uk.co.real_logic.sbe.codec.java.DirectBuffer

import org.midonet.cluster.flowstate.proto.{FlowState, MessageHeader}

object FlowStateSbeEncoder {
    val MessageHeaderVersion = 1
}

class FlowStateSbeEncoder {
    val flowStateHeader = new MessageHeader
    val flowStateMessage = new FlowState
    val flowStateBuffer = new DirectBuffer(new Array[Byte](0))

    def encodeTo(bytes: Array[Byte]): FlowState = {
        flowStateBuffer.wrap(bytes)
        flowStateHeader.wrap(flowStateBuffer, 0,
                             FlowStateSbeEncoder.MessageHeaderVersion)
            .blockLength(flowStateMessage.sbeBlockLength)
            .templateId(flowStateMessage.sbeTemplateId)
            .schemaId(flowStateMessage.sbeSchemaId)
            .version(flowStateMessage.sbeSchemaVersion)
        flowStateMessage.wrapForEncode(flowStateBuffer, flowStateHeader.size)
        flowStateMessage
    }

    def encodedLength(): Int = flowStateHeader.size + flowStateMessage.size

    def decodeFrom(bytes: Array[Byte]): FlowState = {
        flowStateBuffer.wrap(bytes)
        flowStateHeader.wrap(flowStateBuffer, 0,
                             FlowStateSbeEncoder.MessageHeaderVersion)
        val templateId = flowStateHeader.templateId
        if (templateId != FlowState.TEMPLATE_ID) {
            throw new IllegalArgumentException(
                s"Invalid template id for flow state $templateId")
        }
        flowStateMessage.wrapForDecode(flowStateBuffer, flowStateHeader.size,
                                       flowStateHeader.blockLength,
                                       flowStateHeader.version)
        flowStateMessage
    }
}
