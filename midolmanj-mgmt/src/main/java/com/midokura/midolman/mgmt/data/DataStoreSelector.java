/*
 * @(#)DataStoreSelector        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;

/**
 * DAO factory selector.
 *
 * @version 1.6 15 Nov 2011
 * @author Ryu Ishimoto
 */
public class DataStoreSelector {

    private final AppConfig config;

    /**
     * Constructor
     *
     * @param config
     *            AppConfig object where the DAO factory class is specified.
     */
    public DataStoreSelector(AppConfig config) {
        this.config = config;
    }

    /**
     * Get the DAO factory class specified in the configuration file.
     *
     * @return DaoFactory class
     * @throws DaoInitializationException
     *             Initialization error.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public DaoFactory getDaoFactory() throws DaoInitializationException {

        Class clazz = null;

        try {
            clazz = Class.forName(config.getDataStoreClassName());
        } catch (InvalidConfigException e) {
            throw new DaoInitializationException(
                    "Could not get class name from config", e);
        } catch (ClassNotFoundException e) {
            throw new DaoInitializationException(
                    "Could not find class defined in config", e);
        }

        Class[] argsClass = new Class[] { AppConfig.class };
        Constructor dataStoreConstructor = null;
        try {
            dataStoreConstructor = clazz.getConstructor(argsClass);
        } catch (SecurityException e) {
            throw new DaoInitializationException(
                    "Class specified in the config could not be secuirely constructed.",
                    e);
        } catch (NoSuchMethodException e) {
            throw new DaoInitializationException(
                    "Class specified in the config does not have constructor.",
                    e);
        }

        DaoFactory factory = null;
        Object[] args = new Object[] { config };
        try {

            factory = (DaoFactory) dataStoreConstructor.newInstance(args);
        } catch (InstantiationException e) {
            throw new DaoInitializationException(
                    "Class specified in the config cannot be instantiated", e);
        } catch (IllegalAccessException e) {
            throw new DaoInitializationException(
                    "Class specified in the config is not accessible", e);
        } catch (IllegalArgumentException e) {
            throw new DaoInitializationException(
                    "AbstractDaoFactory constructor is not properly defined.",
                    e);
        } catch (InvocationTargetException e) {
            throw new DaoInitializationException(
                    "AbstractDaoFactory constructor threw an exception.", e);
        }

        return factory;
    }
}
