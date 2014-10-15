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

package org.midonet.brain.api;

import org.midonet.brain.api.services.TopologyApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application to start the TopologyApiService
 */
public class TopologyApi {
    private static final Logger log = LoggerFactory.getLogger(TopologyApi.class);

    private final TopologyApiService api = new TopologyApiService();

    private void run() {
        api.startAsync().awaitRunning();
    }

    private void cleanup() {
        api.stopAsync().awaitTerminated();
    }

    private static class BrainApiHolder {
        private static final TopologyApi instance = new TopologyApi();
    }

    public static TopologyApi getInstance() {
        return BrainApiHolder.instance;
    }

    public static void main(String[] args) {
        int exit_value = 0;
        TopologyApi app = null;
        try {
            log.info("Starting");
            app = getInstance();
            app.run();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(600000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                log.info("Interrupted. Shutting down");
                app.cleanup();
            }
        } catch (Exception e) {
            log.error("Failed service start");
            app.cleanup();
            exit_value = -1;
        } finally {
            System.exit(exit_value);
        }
    }
}
