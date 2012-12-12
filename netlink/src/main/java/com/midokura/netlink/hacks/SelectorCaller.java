/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.hacks;

import java.lang.reflect.Method;
import java.nio.channels.Selector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.SelectionKeyImpl;

/**
 * This class is a hack to allow using a custom socket implementation until jdk8
 * rolls over across the project.
 *
 * @deprecated since jdk8
 */
public class SelectorCaller {

    private static final Logger log = LoggerFactory
        .getLogger(SelectorCaller.class);

    private static Method putEventOpsRef = null;

    /**
     * This method should be replaced with sk.selector.putEventOps(sk, ops);
     *
     * @deprecated since jdk8
     */
    public static void putEventOps(Selector selector, SelectionKeyImpl sk, int ops) {

        Method method = putEventOpsRef;

        if (method == null) {
            try {
                method = selector.getClass().getDeclaredMethod("putEventOps", sun.nio.ch.SelectionKeyImpl.class, int.class);
                method.setAccessible(true);
            } catch (Exception ex) {
                log.error("Exception while retrieving selector method, ", ex);
            }
        }

        if (method != null ) {
            try {
                method.invoke(selector, sk, ops);
            } catch (Exception e) {
                log.error("Exception while invoking putEventOps, ", e);
            }
        }

        putEventOpsRef = method;
    }
}
