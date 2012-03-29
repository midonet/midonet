/*
 * @(#)KeystoneClientFactory.java        1.6 12/03/29
 *
 * Copyright 2012 Midokura KK
 */

package com.midokura.midolman.mgmt.auth;

import java.io.IOException;
import java.lang.reflect.Constructor;

import javax.servlet.FilterConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.config.InvalidConfigException;

/**
 * Factory class for Keystone Client.
 *
 * @version 1.6 29 Mar 2012
 * @author Yoshi Tamura
 */
public final class KeystoneClientFactory {
    /**
     * Implements Keystone authentication methods.
     */
    private final static Logger log = LoggerFactory
        .getLogger(KeystoneClientFactory.class);

    /**
     * Default constructor for KeystoneClient.
     *
     * @param config
     *            FileterConfig for initialization.
     * @return KeystoneClient object.
     * @throws InvalidConfigException
     *             Configuration error.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static KeystoneClient create(FilterConfig config)
        throws InvalidConfigException {

        Class clazz = null;
        String className = config.getInitParameter("client_type");
        try {
            if (className == null) {
                clazz = KeystoneClientImpl.class;
            } else {
                clazz = Class.forName(className);
            }
        } catch (ClassNotFoundException e) {
            throw new InvalidConfigException(
                "Could not find class defined in config", e);
        }

        Class[] argsClass = new Class[] { String.class, String.class,
                                          int.class };
        Constructor clientConstructor = null;
        try {
            clientConstructor = clazz.getConstructor(argsClass);
        } catch (NoSuchMethodException e) {
            throw new InvalidConfigException(
                "Class specified in the config doesn't have constructor.", e);
        }

        KeystoneClient client = null;
        try {
            String protocol = config.getInitParameter("service_protocol");
            String host = config.getInitParameter("service_host");
            int port = Integer.parseInt(
                config.getInitParameter("service_port"));

            Object[] args = new Object[] { protocol, host, port };
            log.debug(
                "Instanticating {} with protocol {} host {} port {}",
                new Object[] { clazz.getName(), protocol, host, port });
            client = (KeystoneClient) clientConstructor.newInstance(args);
        } catch (Exception e) {
            throw new InvalidConfigException(
                "Cannot instantiate Keystone client from the config ", e);
        }

        return client;
    }
}
