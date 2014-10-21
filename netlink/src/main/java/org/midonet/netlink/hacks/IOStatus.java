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

import java.lang.reflect.Field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is a hack to allow using a custom socket implementation until jdk8
 * rolls over across the project.
 *
 * @deprecated since jdk8
 *
 * @see sun.nio.ch.IOStatus
 */
public class IOStatus {

    private static final Logger log = LoggerFactory
        .getLogger(IOStatus.class);

    private static Class ioStatusClassRef;

    public static final int EOF =
        getIntField("EOF");                     // End of file

    public static final int UNAVAILABLE =
        getIntField("UNAVAILABLE");             // Nothing available (non-blocking)

    public static final int INTERRUPTED =
        getIntField("INTERRUPTED");             // System call interrupted

    public static final int UNSUPPORTED =
        getIntField("UNSUPPORTED");             // Operation not supported

    public static final int THROWN =
        getIntField("THROWN");                  // Exception thrown in JNI code

    public static final int UNSUPPORTED_CASE =
        getIntField("UNSUPPORTED_CASE");        // This case not supported

    private synchronized static int getIntField(String fieldName) {
        if ( ioStatusClassRef == null ) {
            try {
                ioStatusClassRef =
                    IOStatus.class.getClassLoader().loadClass("sun.nio.ch.IOStatus");
            } catch (Exception e) {
                log.error("Exception initializing sun.nio.ch.IOStatus class reference", e);
            }
        }

        if ( ioStatusClassRef != null ) {
            try {
                Field fieldRef = ioStatusClassRef.getDeclaredField(fieldName);
                fieldRef.setAccessible(true);
                return (Integer) fieldRef.get(null);
            } catch (Exception e) {
                log.error(
                    "Exception loading the field value for sun.nio.ch.IOStatus.{}",
                    fieldName, e);
            }
        }

        return Integer.MIN_VALUE;
    }

    public static int normalize(int n) {
        if (n == UNAVAILABLE)
            return 0;
        return n;
    }

    public static boolean check(int n) {
        return (n >= UNAVAILABLE);
    }

    public static long normalize(long n) {
        if (n == UNAVAILABLE)
            return 0;
        return n;
    }

    public static boolean check(long n) {
        return (n >= UNAVAILABLE);
    }

    // Return true iff n is not one of the IOStatus values
    public static boolean checkAll(long n) {
        return ((n > EOF) || (n < UNSUPPORTED_CASE));
    }
}
