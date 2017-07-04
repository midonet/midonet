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

package org.midonet.midolman.monitoring;

public class NativeMeterRegistryJNI {
    public static native long create();
    public static native String[] getMeterKeys(long registry);
    public static native long[] getMeter(long registry, String key);
    public static native void trackFlow(long registry, byte[] flowMatch,
                                        String[] tags);
    public static native void recordPacket(long registry, int packetLength,
                                           String[] tags);
    public static native void updateFlow(long registry, byte[] flowMatch,
                                         long packets, long bytes);
    public static native void forgetFlow(long registry, byte[] flowMatch);
}