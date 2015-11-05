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

package org.midonet.cluster.util.logging

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message

import org.midonet.cluster.models.Commons
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}

/** Protobufs offer a TextFormat.shortDebugString that almost does what we
  * want, although it doesn't understand our Commons messages, which
  * resuls in UUIDs split in msb/lsb, etc.
  */
object ProtoTextPrettifier {

    /** Returns a string representation of the given object, that must
      * not be null.  It'll simply return o.toString()
      */
    def makeReadable(o: Any): String = o.toString

    /** Returns a string representation of the given protobuf, considering
      * [[Commons]] types such as UUID, IP address, etc.
      *
      * It does not tolerate nulls.
      */
    def makeReadable(m: Message): String = {
        if (isCommons(m)) {
            return printValue(m)
        }
        val sb = new StringBuilder(nameOf(m)).append(": { ")
        val allFields = m.getAllFields.entrySet().iterator()
        var printedField = false
        while(allFields.hasNext) {
            val e = allFields.next
            val fDesc = e.getKey
            val fVal = e.getValue
            if (fVal != null) {
                if (printedField) {
                    sb.append(", ")
                } else {
                    printedField = true
                }
                printField(fDesc, fVal, sb)
            }
        }
        sb.append("}")
        sb.toString()
    }

    def printField(fDesc: FieldDescriptor, fVal: AnyRef, sb: StringBuilder): Unit = {
        if (fDesc.isRepeated) {
            val vals = fVal.asInstanceOf[java.lang.Iterable[_]].iterator
            sb.append(fDesc.getName).append(": [")
            var printedField = false
            while (vals.hasNext) {
                if (printedField) {
                    sb.append(", ")
                } else {
                    printedField = true
                }
                printSingleField(fDesc, vals.next(), sb)
            }
            sb.append("] ")
        } else {
            printSingleField(fDesc, fVal, sb)
        }
    }

    def printSingleField(fDesc: FieldDescriptor, fVal: Any,
                         sb: StringBuilder): Unit = {
        if (fDesc.getJavaType == FieldDescriptor.JavaType.MESSAGE) {
            sb.append(fDesc.getMessageType.getName)
            sb.append(" { ").append(printValue(fVal)).append(" }")
        } else {
            sb.append(fDesc.getName).append(": ")
            if (fDesc.getJavaType == JavaType.STRING) {
                sb.append("\"").append(fVal).append("\"")
            } else {
                sb.append(fVal.toString)
            }
        }
    }

    def isCommons(m: Message): Boolean = {
        m.isInstanceOf[Commons.UUID] ||
        m.isInstanceOf[Commons.IPAddress] ||
        m.isInstanceOf[Commons.IPSubnet] ||
        m.isInstanceOf[Commons.Int32Range]
    }

    /** Receives a leaf message to print (e.g.: no repeats or nested), or a
      * Commons message.
      */
    def printValue(fVal: Any): String = fVal match {
        case uuid: Commons.UUID => UUIDUtil.fromProto(uuid).toString
        case ip: Commons.IPAddress => IPAddressUtil.toIPAddr(ip).toString
        case net: Commons.IPSubnet => IPSubnetUtil.fromProto(net).toString
        case range: Commons.Int32Range =>
            "[" + range.getStart + ", " + range.getEnd + "]"
        case m: Message => makeReadable(m)
        case v => v.toString
    }

    @inline
    def nameOf(m: Message): String = {
        m.getClass.getName
    }

}
