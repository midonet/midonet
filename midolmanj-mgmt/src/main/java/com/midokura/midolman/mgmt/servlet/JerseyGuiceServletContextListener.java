/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.servlet;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceServletContextListener;
import com.midokura.midolman.mgmt.data.dao.ApplicationDao;
import com.midokura.midolman.mgmt.guice.servlet.MidoJerseyServletModule;
import com.midokura.midolman.state.StateAccessException;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;

/**
 * Guice servlet listener.
 */
public class JerseyGuiceServletContextListener extends
        GuiceServletContextListener {

    protected ServletContext servletContext;
    protected Injector injector;

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        servletContext = servletContextEvent.getServletContext();
        super.contextInitialized(servletContextEvent);
    }

    protected void initializeApplication() {
        ApplicationDao dao = injector.getInstance(ApplicationDao.class);

        try {
            dao.initialize();
        } catch (StateAccessException e) {
            throw new RuntimeException("Could not initialize application", e);
        }
    }

    @Override
    protected Injector getInjector() {

        injector = Guice.createInjector(
                new MidoJerseyServletModule(servletContext));

        // Initialize application
        initializeApplication();

        return injector;
    }

}