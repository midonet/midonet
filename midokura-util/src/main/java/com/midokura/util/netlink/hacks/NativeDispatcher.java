/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.hacks;

import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is a hack to allow using a custom socket implementation until jdk8
 * rolls over across the project.
 *
 * @deprecated since jdk8
 *
 * @see sun.nio.ch.NativeDispatcher
 */
public class NativeDispatcher {

    private static final Logger log = LoggerFactory
        .getLogger(NativeDispatcher.class);

    public static Class nativeDispatcherClass;
    public static Method preCloseRef;

    Object dispatcher;

    static {
        try {
            nativeDispatcherClass =
                IOUtil.class.getClassLoader().loadClass("sun.nio.ch.NativeDispatcher");

            preCloseRef = nativeDispatcherClass.getDeclaredMethod("preClose", FileDescriptor.class);
            preCloseRef.setAccessible(true);
        } catch (Exception e) {
            log.error("Exception initializing sun.nio.ch.IOUtil method ref", e);
        }
    }

    public NativeDispatcher() {
        try  {
            Class<?> aClass = Class.forName("sun.nio.ch.DatagramDispatcher");
            Constructor  constructor = aClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            dispatcher = constructor.newInstance();
        } catch (Exception ex) {
            log.error("Error creating sun.nio.ch.DatagramDispatcher instance", ex);
        }
    }

    public void preClose(FileDescriptor fd) {
        try  {
            preCloseRef.invoke(dispatcher, fd);
        } catch (Exception ex) {
            log.error("Error while invoking method {}", preCloseRef, ex);
        }
    }
}
