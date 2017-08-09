/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.netlink.hacks;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is a hack to allow using a custom socket implementation.
 *
 * @see sun.nio.ch.IOUtil
 */
public class IOUtil {

    private static final Logger log = LoggerFactory
        .getLogger(IOUtil.class);

    private static Class<?> ioUtilClassRef;
    private static Method writeRef;
    private static Method writeMultipleRef;
    private static Method readRef;
    private static Method readMultipleRef;

    static {
        try {
            ioUtilClassRef =
                IOUtil.class.getClassLoader().loadClass("sun.nio.ch.IOUtil");
        } catch (Exception e) {
            log.error("Exception retrieving sun.nio.ch.IOUtil class loader: {}", e);
        }

        try {
            writeRef = ioUtilClassRef.getDeclaredMethod("write", FileDescriptor.class, ByteBuffer.class, long.class, NativeDispatcher.nativeDispatcherClass);
            writeRef.setAccessible(true);

            readRef = ioUtilClassRef.getDeclaredMethod("read", FileDescriptor.class, ByteBuffer.class, long.class, NativeDispatcher.nativeDispatcherClass);
            readRef.setAccessible(true);

            writeMultipleRef = ioUtilClassRef.getDeclaredMethod("write", FileDescriptor.class, ByteBuffer[].class, NativeDispatcher.nativeDispatcherClass);
            writeMultipleRef.setAccessible(true);

            readMultipleRef = ioUtilClassRef.getDeclaredMethod("read", FileDescriptor.class, ByteBuffer[].class, NativeDispatcher.nativeDispatcherClass);
            readMultipleRef.setAccessible(true);
        } catch (Exception e) {
            log.error("Exception initializing sun.nio.ch.IOUtil method ref: {}", e);
        }
    }

    public static int write(FileDescriptor fd, ByteBuffer src, long position,
                            NativeDispatcher nd) {
        try {
            return (Integer)writeRef.invoke(null, fd, src, position, nd.dispatcher);
        } catch (Exception e) {
            log.error("Error invoking method {}", writeRef, e);
        }
        return -1;
    }

    public static long write(FileDescriptor fd, ByteBuffer[] buffers, NativeDispatcher nd) {
        try {
            return (Long)writeMultipleRef.invoke(null, fd, buffers, nd.dispatcher);
        } catch (Exception e) {
            log.error("Error invoking method \"{}\"", writeMultipleRef, e);
        }
        return -1;
    }

    public static int read(FileDescriptor fd, ByteBuffer src, long position,
                            NativeDispatcher nd) {
        try {
            return (Integer)readRef.invoke(null, fd, src, position, nd.dispatcher);
        } catch (Exception e) {
            log.error("Error invoking method {}", readRef, e);
        }
        return -1;
    }

    public static long read(FileDescriptor fd, ByteBuffer[] buffers, NativeDispatcher nd) {
        try {
            return (Long)readMultipleRef.invoke(null, fd, buffers, nd.dispatcher);
        } catch (Exception e) {
            log.error("Error invoking method \"{}\"", readMultipleRef, e);
        }
        return -1;
    }
}
