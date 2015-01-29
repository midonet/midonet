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

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.TestingServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.rest_api.FuncTest;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.state.ZkConnection;
import org.midonet.midolman.state.zkManagers.FiltersZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;

public class JerseyGuiceTestServletContextListener extends
        JerseyGuiceServletContextListener {

    private static final Logger log =
        LoggerFactory.getLogger(JerseyGuiceTestServletContextListener.class);
    private static Injector _injector = null;

    private TestingServer testZk;

    public JerseyGuiceTestServletContextListener() {
        try {
            testZk = new TestingServer(FuncTest.ZK_TEST_PORT);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start zookeeper server");
        }
    }

    protected void initializeApplication() {
        log.debug("initializeApplication: entered");

        // We do this here so we can have a real ZK available by the moment that
        // the MidonestoreSetupService comes up. This way, the API starts as if
        // it was a real production environment (no MockDirectory, etc.)
        try {
            testZk.start();
        } catch (Exception e) {
            log.error("Cannot start zookeeper server");
        }

        // This allows a backdoor from tests into the API's injection framwework
        // see getHostZkManager for info
        _injector = injector;

        super.initializeApplication();

        log.debug("initializeApplication: exiting");
    }

    /**
     * This method provides access to the HostZkManager that is injected in the
     * Jersey web app used for tests.
     *
     * This is a special case because Hosts cannot be created through the REST
     * API so it's justified to get the HostsZkManager to create them. For this
     * purpose we allow the HostTopology class to retrieve it.
     *
     * Please:
     *
     * - AVOID using the HostZkManager yourself. Use HostTopology, this allows
     *   us to control dependencies on this hack.
     * - DO NOT add more similar methods, much less one getInstance(Class<T> k)
     *   to get the bound instance of a random class. This is wrong: REST API
     *   tests should only be written against the REST API and not use backdoors
     *   to internal components.
     */
    public static HostZkManager getHostZkManager() {
        return _injector.getInstance(HostZkManager.class);
    }

    /**
     * Read comments in getHostZkManager.
     */
    public static CuratorFramework getCurator() {
        return _injector.getInstance(CuratorFramework.class);
    }

    /**
     * Read comments in getHostZkManager.
     */
    public static FiltersZkManager getFiltersZkManager() {
        return _injector.getInstance(FiltersZkManager.class);
    }

    /**
     * Read comments in getHostZkManager.
     */
    public static RouterZkManager getRouterZkManager() {
        return _injector.getInstance(RouterZkManager.class);
    }

    @Override
    protected void destroyApplication() {
        log.debug("destroyApplication: entered");

        super.destroyApplication();

        // This seems to be required or the test zk server believes that the
        // connection is not closed and eventually maxes out client conns.
        // I think the storage services do not close this connection by
        // themselves, probably in order to keep sessions open intentionally
        injector.getInstance(ZkConnection.class).close();

        try {
            testZk.close();
        } catch (Exception e) {
            log.error("Could not close testing zookeeper server", e);
        }

        log.debug("destroyApplication: exiting");
    }

    @Override
    protected Injector getInjector() {
        log.debug("getInjector: entered.");

        injector = Guice.createInjector(
            new RestApiTestJerseyServletModule(servletContext));

        // Initialize application
        initializeApplication();

        log.debug("getInjector: exiting.");
        return injector;
    }

}
