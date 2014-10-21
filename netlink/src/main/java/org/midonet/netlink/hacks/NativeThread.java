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

import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is a hack to allow using a custom socket implementation until jdk8
 * rolls over across the project.
 *
 * @deprecated since jdk8
 *
 * @see sun.nio.ch.NativeThread
 */
public class NativeThread {

    private static final Logger log = LoggerFactory
        .getLogger(NativeThread.class);

    static Class nativeThreadClass;
    static Method currentMethod;
    static Method signalMethod;

    static {
        try {
            nativeThreadClass = Class.forName("sun.nio.ch.NativeThread");
            currentMethod = nativeThreadClass.getDeclaredMethod("current");
            currentMethod.setAccessible(true);

            signalMethod = nativeThreadClass.getDeclaredMethod("signal", long.class);
            signalMethod.setAccessible(true);
        } catch (Exception ex) {
            log.error("Exception while loading class and method references for \"sun.nio.ch.NativeThread\"", ex);
        }
    }

    static public long current() {
        try {
            return (Long)currentMethod.invoke(null);
        } catch (Exception ex) {
            log.error("Exception invoking NativeThread.current()", ex);
        }

        return -1;
    }

    public static void signal(long th) {
        try {
            signalMethod.invoke(null, th);
        } catch (Exception ex) {
            log.error("Exception invoking method: \"{}\"", signalMethod, ex);
        }
    }
}
