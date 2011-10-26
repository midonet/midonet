package com.midokura.midolman.mgmt.servlet.listeners;

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
