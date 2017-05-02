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
package org.midonet.util.concurrent;

public class NativeTimedExpirationMap {
    public native long create();
    public native byte[] putAndRef(long mapPointer, byte[] key, byte[] value);
    public native int putIfAbsentAndRef(long mapPointer, byte[] key, byte[] value);
    public native byte[] get(long mapPointer, byte[] key);
    public native int getRefCount(long mapPointer, byte[] key);
    public native byte[] ref(long mapPointer, byte[] key);
    public native int refAndGetCount(long mapPointer, byte[] key);
    public native int refCount(long mapPointer, byte[] key);
    public native byte[] unref(long mapPointer, byte[] key,
                               long expireIn,
                               long currentTimeMillis);
    public native void destroy(long mapPointer);

    public native long iterator(long mapPointer);
    public native boolean iteratorAtEnd(long iteratorPointer);
    public native void iteratorNext(long iteratorPointer);
    public native byte[] iteratorCurKey(long iteratorPointer);
    public native byte[] iteratorCurValue(long iteratorPointer);
    public native void iteratorClose(long iteratorPointer);
}
