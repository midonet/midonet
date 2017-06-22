/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.midolman.flows

import java.nio.ByteBuffer

import org.midonet.midolman.flows.{NativeFlowMatchListJNI => JNI}
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.odp.{FlowMatch, FlowMatches}

object NativeFlowMatchList {

    var loaded = false
    def loadNativeLibrary() = synchronized {
        if (!loaded) {
            System.loadLibrary("nativeFlowMatchList")
            loaded = true
        }
    }
}

class NativeFlowMatchList extends MidolmanLogging {

    private var flowMatchList = Option(JNI.createFlowMatchList())

    private def fail =
        throw new Exception("Operation on a deleted flow list.")

    /**
      * This method pushes off-heap a portion of the byte array
      * passed by parameter in the range [position, position + remaining).
      * This ByteBuffer MUST have its position and limit in the corresponding
      * limits of the flow match, as those will be used to delimit which bytes
      * to copy to off-heap.
      */
    def pushFlowMatch(fmatch: ByteBuffer): Unit = {
        val flowList = flowMatchList.getOrElse(fail)
        JNI.pushFlowMatch(flowList, fmatch, fmatch.position(), fmatch.remaining())
    }

    /**
      * This method pops (get and remove) an element from the list
      * in FIFO order. The raw buffer is copied to on-heap and
      * deserialized.
      */
    def popFlowMatch(): FlowMatch = {
        val flowList = flowMatchList.getOrElse(fail)
        if (JNI.size(flowList) == 0) {
            throw new NoSuchElementException
        }
        val bytes = JNI.popFlowMatch(flowList)
        FlowMatches.fromBytes(bytes)
    }

    /**
      * Returns the current number of flow matches in the list
      */
    def size(): Int = {
        val flowList = flowMatchList.getOrElse(fail)
        JNI.size(flowList)
    }

    /**
      * De-allocates any off-heap memory allocated. MUST be called once
      * all flow matches have been pulled out of the list and invalidated
      * from the datapath.
      */
    def delete(): Unit = {
        flowMatchList.foreach { l =>
            JNI.deleteFlowMatchList(l)
            flowMatchList = None
        }
    }
}
