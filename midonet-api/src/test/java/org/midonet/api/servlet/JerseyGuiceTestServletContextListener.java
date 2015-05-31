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

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.curator.test.TestingServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.TopologyBackdoor;
import org.midonet.midolman.state.ZkConnection;

public class JerseyGuiceTestServletContextListener extends
        JerseyGuiceServletContextListener {

    private static final Logger log =
        LoggerFactory.getLogger(JerseyGuiceTestServletContextListener.class);

    private TestingServer testZk;

    public JerseyGuiceTestServletContextListener() {
        try {
            testZk = new TestingServer(FuncTest.ZK_TEST_PORT);
        } catch (Exception e) {
            throw new IllegalStateException("Can't start Zookeeper server at " +
                                             FuncTest.ZK_TEST_PORT);
        }
    }

    protected void initializeApplication() {
        log.debug("initializeApplication: entered");

        // This allows a backdoor from tests into the API's injection framwework
        // see getHostZkManager for info
        FuncTest._injector = injector;

        // We do this here so we can have a real ZK available by the moment that
        // the MidonestoreSetupService comes up. This way, the API starts as if
        // it was a real production environment (no MockDirectory, etc.)
        try {
            testZk.start();
        } catch (Exception e) {
            log.error("Cannot start zookeeper server");
        }

        super.initializeApplication();

        log.debug("initializeApplication: exiting");
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
    protected void startConfApi() { }

    @Override
    protected void stopConfApi() { }

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
