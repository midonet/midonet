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

    public static native byte[] flowTableFlowMatch(long flowTable, long id);
    public static native long flowTableFlowSequence(long flowTable, long id);
    public static native void flowTableFlowSetSequence(
            long flowTable, long id, long sequence);
    public static native long flowTableFlowLinkedId(long flowTable, long id);
    public static native void flowTableFlowSetLinkedId(
            long flowTable, long id, long linkedId);

    public static native void flowTableFlowAddCallback(
            long flowTable, long id, long cbId, byte[] args);
    public static native int flowTableFlowCallbackCount(long flowTable, long id);
    public static native long flowTableFlowCallbackId(
            long flowTable, long id, int index);
    public static native byte[] flowTableFlowCallbackArgs(
            long flowTable, long id, int index);

    public static native long createFlowTagIndexer();
    public static native void flowTagIndexerIndexFlowTag(
            long indexer, long id, long tag);
    public static native void flowTagIndexerRemoveFlow(long indexer, long id);
    public static native long flowTagIndexerInvalidate(long indexer, long tag);
    public static native long flowTagIndexerInvalidFlowsCount(long invalids);
    public static native long flowTagIndexerInvalidFlowsGet(long invalids,
                                                            int index);
    public static native long flowTagIndexerInvalidFlowsFree(long invalids);

    public static native long createFlowExpirationIndexer();
    public static native void flowExpirationIndexerEnqueueFlowExpiration(
            long expirer, long id, long expiration, int expirationType);
    public static native long flowExpirationIndexerPollForExpired(
            long expirer, long expiration);
    public static native long flowExpirationIndexerEvictFlow(
            long expirer);

}
