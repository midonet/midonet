/*
 * Copyright 2015 Midokura SARL
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
import org.midonet.cluster.ClusterConfig;
import org.midonet.cluster.ClusterNode;
import org.midonet.cluster.services.conf.ConfMinion;
import org.midonet.cluster.services.vxgw.VxlanGatewayService;
import org.midonet.cluster.southbound.vtep.VtepDataClientFactory;

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

        injector.getInstance(RestApiService.class).startAsync().awaitRunning();
        ClusterNode.Context ctx = injector.getInstance(ClusterNode.Context.class);
        ClusterConfig clusterConf = injector.getInstance(ClusterConfig.class);

        startConfApi();

        if (ctx.embed() && clusterConf.vxgw().isEnabled()) {
            log.info("initializeApplication: starting embedded Cluster node");
            injector.getInstance(VxlanGatewayService.class)
                    .startAsync()
                    .awaitRunning();
        } else {
            log.warn("initializeApplication: skip embedded Cluster node - "
                     + "Control functions for VxLAN Gateway will be inactive. "
                     + "If this is not intentional, change the "
                     + "cluster.vxgw.enabled config setting");
        }

    }

    protected void startConfApi() {
        log.info("Launching embedded configuration service");
        injector.getInstance(ConfMinion.class).startAsync().awaitRunning();
    }

    protected void stopConfApi() {
        log.info("Stopping embedded configuration service");
        injector.getInstance(ConfMinion.class).stopAsync().awaitTerminated();
    }

    protected void destroyApplication() {
        log.debug("destroyApplication: entered");

        ClusterNode.Context ctx = injector.getInstance(ClusterNode.Context.class);
        ClusterConfig clusterConf = injector.getInstance(ClusterConfig.class);

        if (ctx.embed() && clusterConf.vxgw().isEnabled()) {
            log.info("Stopping embedded Cluster service for node {}",
                     ctx.nodeId());
            injector.getInstance(VxlanGatewayService.class)
                    .stopAsync()
                    .awaitTerminated();
        } else {
            log.info("destroyApplication: skipping embedded Cluster node");
        }

        stopConfApi();

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
