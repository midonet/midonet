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
 * This class is a hack to allow using a custom socket implementation until jdk8
 * rolls over across the project.
 *
 * @deprecated since jdk8
 *
 * @see sun.nio.ch.IOUtil
 */
public class IOUtil {

    private static final Logger log = LoggerFactory
        .getLogger(IOUtil.class);

    private static Class ioUtilClassRef;
    private static Method fdValRef;
    private static Method newFdRef;
    private static int writeArity;
    private static Method writeRef;
    private static Method writeMultipleRef;
    private static int readArity;
    private static Method readRef;
    private static Method readMultipleRef;
    private static Method configureBlockingRef;

    static {
        try {
            ioUtilClassRef =
                IOUtil.class.getClassLoader().loadClass("sun.nio.ch.IOUtil");
        } catch (Exception e) {
            log.error("Exception retrieving sun.nio.ch.IOUtil class loader: {}", e);
        }

        try {
            writeRef = ioUtilClassRef.getDeclaredMethod("write", FileDescriptor.class, ByteBuffer.class, long.class, NativeDispatcher.nativeDispatcherClass, Object.class);
            writeArity = 5;
            writeRef.setAccessible(true);
        } catch (Exception e) {
            try {
                writeRef = ioUtilClassRef.getDeclaredMethod("write", FileDescriptor.class, ByteBuffer.class, long.class, NativeDispatcher.nativeDispatcherClass);
                writeArity = 4;
                writeRef.setAccessible(true);
            } catch (Exception f) {
                log.error("Exception retrieving handle to sun.nio.ch.IOUtil.write():\n{}\n{}", e, f);
            }
        }

        try {
            readRef = ioUtilClassRef.getDeclaredMethod("read", FileDescriptor.class, ByteBuffer.class, long.class, NativeDispatcher.nativeDispatcherClass, Object.class);
            readArity = 5;
            readRef.setAccessible(true);
        } catch (Exception e) {
            try {
                readRef = ioUtilClassRef.getDeclaredMethod("read", FileDescriptor.class, ByteBuffer.class, long.class, NativeDispatcher.nativeDispatcherClass);
                readArity = 4;
                readRef.setAccessible(true);
            } catch (Exception f) {
                log.error("Exception retrieving handle to sun.nio.ch.IOUtil.read():\n{}\n{}", e, f);
            }
        }

        try {
            fdValRef = ioUtilClassRef.getDeclaredMethod("fdVal", FileDescriptor.class);
            fdValRef.setAccessible(true);

            newFdRef = ioUtilClassRef.getDeclaredMethod("newFD", int.class);
            newFdRef.setAccessible(true);

            writeMultipleRef = ioUtilClassRef.getDeclaredMethod("write", FileDescriptor.class, ByteBuffer[].class, NativeDispatcher.nativeDispatcherClass);
            writeMultipleRef.setAccessible(true);

            readMultipleRef = ioUtilClassRef.getDeclaredMethod("read", FileDescriptor.class, ByteBuffer[].class, NativeDispatcher.nativeDispatcherClass);
            readMultipleRef.setAccessible(true);

            configureBlockingRef = ioUtilClassRef.getDeclaredMethod("configureBlocking", FileDescriptor.class, boolean.class);
            configureBlockingRef.setAccessible(true);
        } catch (Exception e) {
            log.error("Exception initializing sun.nio.ch.IOUtil method ref: {}", e);
        }
    }

    public static FileDescriptor newFD(int fd) {
        try {
            return (FileDescriptor)newFdRef.invoke(null, fd);
        } catch (Exception e) {
            log.error("Error invoking method {}", newFdRef.toString());
        }
        return null;
    }

    public static int fdVal(FileDescriptor fileDescriptor) {
        try {
            return (Integer)fdValRef.invoke(null, fileDescriptor);
        } catch (Exception e) {
            log.error("Error invoking method {}", fdValRef, e);
        }
        return 0;
    }

    public static int write(FileDescriptor fd, ByteBuffer src, long position,
                            NativeDispatcher nd) {
        try {
            if (writeArity == 5)
                return (Integer)writeRef.invoke(null, fd, src, position, nd.dispatcher, null);
            else
                return (Integer)writeRef.invoke(null, fd, src, position, nd.dispatcher);
        } catch (Exception e) {
            log.error("Error invoking method {}", writeRef, e);
        }
        return -1;
    }

    public static int write(FileDescriptor fd, ByteBuffer[] buffers, NativeDispatcher nd) {
        try {
            return (Integer)writeMultipleRef.invoke(null, fd, buffers, nd.dispatcher);
        } catch (Exception e) {
            log.error("Error invoking method \"{}\"", writeMultipleRef, e);
        }
        return -1;
    }

    public static int read(FileDescriptor fd, ByteBuffer src, long position,
                            NativeDispatcher nd) {
        try {
            if (readArity == 5)
                return (Integer)readRef.invoke(null, fd, src, position, nd.dispatcher, null);
            else
                return (Integer)readRef.invoke(null, fd, src, position, nd.dispatcher);
        } catch (Exception e) {
            log.error("Error invoking method {}", readRef, e);
        }
        return -1;
    }

    public static int read(FileDescriptor fd, ByteBuffer[] buffers, NativeDispatcher nd) {
        try {
            return (Integer)readMultipleRef.invoke(null, fd, buffers, nd.dispatcher);
        } catch (Exception e) {
            log.error("Error invoking method \"{}\"", readMultipleRef, e);
        }
        return -1;
    }

    public static void configureBlocking(FileDescriptor fd, boolean block) {
        try {
            configureBlockingRef.invoke(null, fd, block);
        } catch (Exception e) {
            log.error("Error invoking method \"{}\"", configureBlockingRef, e);
        }
    }
}
