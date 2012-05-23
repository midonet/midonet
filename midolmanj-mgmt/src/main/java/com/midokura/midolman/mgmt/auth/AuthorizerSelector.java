/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.dao.TenantDao;

/**
 * Class that selects the authorizer.
 */
public class AuthorizerSelector {

    private final AppConfig config;
    private final TenantDao tenantDao;

    /**
     * Constructor
     *
     * @param config
     *            AppConfig object where the Authorizer class is specified.
     * @param tenantDao
     *            TenantDao object.
     */
    public AuthorizerSelector(AppConfig config, TenantDao tenantDao) {
        this.config = config;
        this.tenantDao = tenantDao;
    }

    /**
     * Get the Authorizer class specified in the configuration file.
     *
     * @return Authorizer class
     * @throws InvalidConfigException
     *             Bad config error.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Authorizer getAuthorizer() throws InvalidConfigException {

        Class clazz;
        try {
            clazz = Class.forName(config.getAuthorizerClassName());
        } catch (ClassNotFoundException e) {
            throw new InvalidConfigException(
                    "Class specified in the config could not be found.", e);
        }

        Class[] argsClass = new Class[] { TenantDao.class };
        Constructor authorizerConstructor = null;
        try {
            authorizerConstructor = clazz.getConstructor(argsClass);
        } catch (SecurityException e) {
            throw new UnsupportedOperationException(
                    "Class specified in the config could not be secuirely constructed.",
                    e);
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException(
                    "Class specified in the config does not have constructor.",
                    e);
        }

        Authorizer auth = null;
        Object[] args = new Object[] { tenantDao };
        try {

            auth = (Authorizer) authorizerConstructor.newInstance(args);
        } catch (InstantiationException e) {
            throw new UnsupportedOperationException(
                    "Class specified in the config cannot be instantiated", e);
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException(
                    "Class specified in the config is not accessible", e);
        } catch (InvocationTargetException e) {
            throw new UnsupportedOperationException(
                    "Authorizer constructor threw an exception.", e);
        }

        return auth;
    }
}
