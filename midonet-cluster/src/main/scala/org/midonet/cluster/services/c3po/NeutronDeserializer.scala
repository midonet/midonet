/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.c3po

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.google.protobuf.Descriptors.FieldDescriptor.{Type => FieldType}
import com.google.protobuf.Descriptors.{Descriptor, EnumDescriptor, EnumValueDescriptor, FieldDescriptor}
import com.google.protobuf.Message

import org.slf4j.LoggerFactory

import org.midonet.cluster.c3poNeutronDeserializerLog
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.{SecurityGroupRule => NeutronSecurityGroupRule, _}
import org.midonet.cluster.rest_api.neutron.models.DeviceOwner
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}

/**
 * Converts Neutron JSON to corresponding Protobuf messages defined in
 * neutron.proto. The conversion relies on the fields in the Protobuf messages
 * having the same names as the as the fields in the JSON. Some field in the
 * Neutron JSON have names in the form "plugin:fieldname," where "plugin" is
 * the name of the plugin which added the field. In this case, the corresponding
 * Protobuf message field should be named "fieldname." For example, a field
 * named "router:external" in the Neutron JSON corresponds to a field named
 * "external" in the Protobuf message.
 */
object NeutronDeserializer {

    private val log = LoggerFactory.getLogger(c3poNeutronDeserializerLog)

    private val jsonFactory = new JsonFactory(new ObjectMapper())

    // Cache message descriptors.
    private val descriptors = new TrieMap[Class[_], Descriptor]()

    /**
     * Converts jsonStr to a Protobuf message of the specified class.
     *
     * @throws NeutronDeserializationException
     *      Should only be thrown in event of data corruption.
     */
    @throws[NeutronDeserializationException]
    def toMessage[M <: Message](jsonStr: String, clazz: Class[M]): M =
        toMessage(parseJson(jsonStr), clazz)

    private def toMessage[M <: Message](node: JsonNode, clazz: Class[M]): M = {
        log.debug("Translating json {} to class {}", Array(node, clazz):_*)
        val bldr = builderFor(clazz)
        val classDesc = descriptorFor(clazz)
        for (field <- node.fields.asScala
             if !nullOrEmptyString(field.getValue)) {
            val name = cleanUpFieldName(field.getKey)
            getFieldDesc(classDesc, name) match {
                case Some(fd) =>
                    val converter = fd.getType match {
                        case FieldType.BOOL => (n: JsonNode) => n.asBoolean
                        case FieldType.ENUM => parseEnum(fd.getEnumType) _
                        case FieldType.INT32 => (n: JsonNode) => n.asInt
                        case FieldType.MESSAGE =>
                                parseNestedMsg(fd.getMessageType) _
                        case FieldType.STRING => (n: JsonNode) => n.asText
                        case FieldType.UINT32 => (n: JsonNode) => n.asInt
                        // Those are the only types we currently use. May need
                        // to add others as we implement more Neutron types.
                        case _ => throw new NeutronDeserializationException(
                            "Don't know how to convert field type " +
                            s"${fd.getType}.")
                    }

                    val value = field.getValue
                    if (fd.isRepeated) {
                        for (child <- value.elements.asScala) {
                            bldr.addRepeatedField(fd, converter(child))
                        }
                    } else {
                        bldr.setField(fd, converter(value))
                    }
                case None =>
                    // Silently ignore non-recognized fields. Avoid breaking
                    // every time upstream Neutron adds whatever new fields.
                    log.debug(
                        s"Field $name in JSON has no corresponding field in " +
                        s"protobuf message class ${classDesc.getName}")
            }
        }

        bldr.build().asInstanceOf[M]
    }

    private def nullOrEmptyString(node: JsonNode): Boolean =
        node.isNull || (node.isTextual && node.asText() == "")

    /* Some fields have a name / value in the form "project:field_or_val". We
     * just ignore the project name part.
     *
     * Also, '-' chars are replaced with '_'
     */
    private def cleanUpFieldName(txt: String) =
        txt.split(':').last.replace('-', '_')

    private def parseNestedMsg(desc: Descriptor)(node: JsonNode): Message = {
        // Have to do this because Protocol Buffers provides no way to get
        // either the class or the builder, or even the fully-qualified class
        // name, from the Descriptor. We need a case for any message class used
        // as the type of a field in another message.
        desc.getFullName match {
            case "org.midonet.cluster.models.UUID" =>
                parseUuid(node.asText)
            case "org.midonet.cluster.models.IPAddress" =>
                parseIpAddr(node.asText)
            case "org.midonet.cluster.models.IPSubnet" =>
                parseIpSubnet(node.asText)
            case "org.midonet.cluster.models.NeutronFirewallRule" =>
                toMessage(node, classOf[NeutronFirewallRule])
            case "org.midonet.cluster.models.NeutronHealthMonitor.Pool" =>
                toMessage(node, classOf[NeutronHealthMonitor.Pool])
            case "org.midonet.cluster.models.NeutronPort.BindingProfile" =>
                toMessage(node, classOf[NeutronPort.BindingProfile])
            case "org.midonet.cluster.models.NeutronPort.ExtraDhcpOpts" =>
                toMessage(node, classOf[NeutronPort.ExtraDhcpOpts])
            case "org.midonet.cluster.models.NeutronPort.IPAllocation" =>
                toMessage(node, classOf[NeutronPort.IPAllocation])
            case "org.midonet.cluster.models.NeutronRoute" =>
                toMessage(node, classOf[NeutronRoute])
            case "org.midonet.cluster.models.NeutronRouter.ExternalGatewayInfo" =>
                toMessage(node, classOf[NeutronRouter.ExternalGatewayInfo])
            case "org.midonet.cluster.models.NeutronSubnet.IPAllocationPool" =>
                toMessage(node, classOf[NeutronSubnet.IPAllocationPool])
            case "org.midonet.cluster.models.SecurityGroupRule" =>
                toMessage(node, classOf[NeutronSecurityGroupRule])
            case "org.midonet.cluster.models.NeutronVIP.SessionPersistence" =>
                toMessage(node, classOf[NeutronVIP.SessionPersistence])
            case "org.midonet.cluster.models.NeutronPort.AllowedAddressPair" =>
                toMessage(node, classOf[NeutronPort.AllowedAddressPair])
            case unknown => throw new NeutronDeserializationException(
                s"Don't know how to deserialize message type $unknown.")
        }
    }

    private def parseEnum(desc: EnumDescriptor)
                         (node: JsonNode): EnumValueDescriptor = {
        // Neutron device_owner for vm ports looks like "compute:<az name>".
        // we map them to "COMPUTE", throwing az away.
        val textVal =
            if (desc.getFullName ==
                "org.midonet.cluster.models.NeutronPort.DeviceOwner"
                && node.asText.startsWith(DeviceOwner.COMPUTE_PREFIX))
                "COMPUTE"
            else
                cleanUpFieldName(node.asText).toUpperCase
        val enumVal = desc.findValueByName(textVal)
        if (enumVal == null)
            throw new NeutronDeserializationException(
                s"Value $textVal not found in enum ${desc.getName}.")
        enumVal
    }

    private def parseUuid(str: String): UUID = {
        try UUIDUtil.toProto(str) catch {
            case ex: Exception => throw new NeutronDeserializationException(
                s"Couldn't parse UUID: $str", ex)
        }
    }

    private def parseIpAddr(str: String): IPAddress = {
        try IPAddressUtil.toProto(str) catch {
            case ex: Exception => throw new NeutronDeserializationException(
                s"Couldn't parse IP address: $str", ex)
        }
    }

    private def parseIpSubnet(str: String): IPSubnet = {
        try IPSubnetUtil.toProto(str) catch {
            case ex: Exception => throw new NeutronDeserializationException(
                s"Couldn't parse IP subnet: $str", ex)
        }
    }

    private def parseJson(jsonStr: String): JsonNode = {
        val parser = jsonFactory.createParser(jsonStr)
        try parser.readValueAsTree() catch {
            case ex: Exception =>
                throw new NeutronDeserializationException(
                    "Could not parse JSON.", ex)
        }
    }

    private def getFieldDesc(classDesc: Descriptor,
                             fieldName: String): Option[FieldDescriptor] = {
        Option(classDesc.findFieldByName(fieldName))
    }

    /**
     * Returns a message Descriptor for the specified class, using a
     * thread-safe cache.
     */
    private def descriptorFor[M <: Message](clazz: Class[M]): Descriptor = {
        descriptors.getOrElseUpdate(clazz, {
            val getter = clazz.getMethod("getDescriptor")
            getter.invoke(null).asInstanceOf[Descriptor]
        })
    }

    private def builderFor(clazz: Class[_]) = {
        assert(classOf[Message].isAssignableFrom(clazz))
        clazz.getMethod("newBuilder").invoke(null).asInstanceOf[Message.Builder]
    }
}

/**
 * This is not a user error, and should never be thrown except in case of bug
 * or data corruption. However, callers should handle it.
 */
class NeutronDeserializationException private[c3po](
        msg: String, cause: Throwable = null) extends Exception(msg, cause)
