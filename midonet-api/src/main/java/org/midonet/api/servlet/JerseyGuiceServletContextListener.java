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
package org.midonet.api.servlet;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceServletContextListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.rest_api.RestApiService;
import org.midonet.brain.configuration.MidoBrainConfig;
import org.midonet.brain.services.vxgw.VxLanGatewayService;
import org.midonet.brain.southbound.vtep.VtepDataClientFactory;

/**
 * Guice servlet listener.
 */
public class JerseyGuiceServletContextListener extends
        GuiceServletContextListener {

    private final static Logger log = LoggerFactory
        .getLogger(JerseyGuiceServletContextListener.class);

    protected ServletContext servletContext;
    protected Injector injector;

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        log.debug("contextInitialized: entered");

        servletContext = servletContextEvent.getServletContext();
        super.contextInitialized(servletContextEvent);

        log.debug("contextInitialized exiting");
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        log.debug("contextDestroyed: entered");

        destroyApplication();
        super.contextDestroyed(servletContextEvent);

        log.debug("contextDestroyed exiting");
    }

    protected void initializeApplication() {
        log.debug("initializeApplication: entered");

        // TODO: Once the cluster work is completed, RestApiService may not be
        // needed since currently it only initializes the ZK root directories.

        injector.getInstance(RestApiService.class).startAsync().awaitRunning();

        if (injector.getInstance(MidoBrainConfig.class).getVxGwEnabled()) {
            log.info("initializeApplication: starting VxLAN gateway");
            injector.getInstance(VxLanGatewayService.class)
                .startAsync()
                .awaitRunning();
        } else {
            log.info("initializeApplication: skipping VxLAN gateway");
        }

        log.debug("initializeApplication: exiting");
    }

    protected void destroyApplication() {
        log.debug("destroyApplication: entered");

        // TODO: Check if we need to do this after the cluster is completed.
        if (injector.getInstance(MidoBrainConfig.class).getVxGwEnabled()) {
            log.info("Stopping VxLanGatewayService");
            injector.getInstance(VxLanGatewayService.class)
                .stopAsync()
                .awaitTerminated();
        }
        injector.getInstance(RestApiService.class)
            .stopAsync()
            .awaitTerminated();
        injector.getInstance(VtepDataClientFactory.class)
            .dispose();

        log.debug("destroyApplication: exiting");
    }

    @Override
    protected Injector getInjector() {
        log.debug("getInjector: entered.");

        injector = Guice.createInjector(
                new RestApiJerseyServletModule(servletContext));

        // Initialize application
        initializeApplication();

        log.debug("getInjector: exiting.");
        return injector;
    }

}
