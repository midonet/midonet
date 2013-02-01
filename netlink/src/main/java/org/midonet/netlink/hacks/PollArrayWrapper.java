/*
* Copyright 2012 Midokura Europe SARL
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
 * @see sun.nio.ch.PollArrayWrapper
 */
public class PollArrayWrapper {

    private static final Logger log = LoggerFactory
        .getLogger(PollArrayWrapper.class);

    public static final short POLLNVAL = getShortField("POLLNVAL");
    public static final short POLLERR = getShortField("POLLERR");
    public static final short POLLHUP = getShortField("POLLHUP");
    public static final short POLLIN = getShortField("POLLIN");
    public static final short POLLOUT = getShortField("POLLOUT");

    private static short getShortField(String fieldName) {

        Class pollWrapperClass;

        try {
            pollWrapperClass =
                IOUtil.class.getClassLoader().loadClass("sun.nio.ch.PollArrayWrapper");

            Field field = pollWrapperClass.getSuperclass().getDeclaredField(
                fieldName);
            field.setAccessible(true);

            Object value = field.get(null);
            if ( value instanceof Short ) {
                return (Short) value;
            }

            return -1;
        } catch (Exception e) {
            log.error("Exception reading value of field sun.nio.ch.PollArrayWrapper#{}", fieldName, e);
        }

        return -1;
    }
}
