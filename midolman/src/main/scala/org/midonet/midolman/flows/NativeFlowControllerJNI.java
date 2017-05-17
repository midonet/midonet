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
package org.midonet.midolman.flows;

public class NativeFlowControllerJNI {
    public static native long createFlowTable(int maxFlows);
    public static native long flowTablePutFlow(long flowTable, byte[] flowMatch);
    public static native long flowTableClearFlow(long flowTable, long id);

    public static native long flowTableIdAtIndex(long flowTable, int index);
    public static native int flowTableOccupied(long flowTable);
    public static native long flowTableEvictionCandidate(long flowTable);

    public static native byte[] flowTableFlowMatch(long flowTable, long id);
    public static native long flowTableFlowSequence(long flowTable, long id);
    public static native void flowTableFlowSetSequence(
            long flowTable, long id, long sequence);
    public static native long flowTableFlowLinkedId(long flowTable, long id);
    public static native void flowTableFlowSetLinkedId(
            long flowTable, long id, long linkedId);
}
