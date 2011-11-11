/*
 * @(#)ServletListener        1.6 11/11/11
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.servlet;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import com.midokura.midolman.mgmt.config.AppConfig;

public class ServletListener implements ServletContextListener {

    @Override
    public void contextDestroyed(ServletContextEvent ctx) {
        // Do nothing
    }

    @Override
    public void contextInitialized(ServletContextEvent ctx) {
        AppConfig.init(ctx.getServletContext());
    }
}
