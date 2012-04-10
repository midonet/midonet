/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import java.lang.reflect.Constructor;

import javax.servlet.FilterConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.auth.keystone.KeystoneClient;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.servlet.ServletSupport;

/**
 * Factory class for auth client.
 */
public final class AuthClientFactory {

    private final static Logger log = LoggerFactory
            .getLogger(AuthClientFactory.class);

    /**
     * Instantiates an appropriate AuthClient object.
     *
     * @param config
     *            FileterConfig for initialization.
     * @return AuthClient object.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static AuthClient create(FilterConfig config) {
        log.debug("AuthClientFactory: entered create.");

        Class clazz = null;
        String className = config
                .getInitParameter(ServletSupport.AUTH_CLIENT_CONFIG_KEY);
        if (className == null) {
            // Default to Keystone
            clazz = KeystoneClient.class;
        } else {
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                throw new InvalidConfigException(
                        "Could not find class defined in config", e);
            }
        }

        Class[] argsClass = new Class[] { FilterConfig.class };
        Constructor clientConstructor = null;
        try {
            clientConstructor = clazz.getConstructor(argsClass);
        } catch (NoSuchMethodException e) {
            throw new InvalidConfigException(
                    "Class specified in the config doesn't have constructor.",
                    e);
        }

        AuthClient client = null;
        try {
            client = (AuthClient) clientConstructor.newInstance(config);
        } catch (Exception e) {
            throw new InvalidConfigException(
                    "Cannot instantiate AuthClient from the config ", e);
        }

        log.debug("AuthClientFactory: exiting create.");
        return client;
    }
}
