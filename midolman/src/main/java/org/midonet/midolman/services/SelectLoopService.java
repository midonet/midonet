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
package org.midonet.midolman.services;

import java.io.IOException;

import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.guice.MidolmanActorsModule;
import org.midonet.util.eventloop.SelectLoop;

/**
 * Service implementation that will initialize the SelectLoop select thread.
 */
public class SelectLoopService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(SelectLoopService.class);

    @Inject
    @MidolmanActorsModule.ZEBRA_SERVER_LOOP
    SelectLoop zebraLoop;

    private Thread startLoop(final SelectLoop loop, final String name) {
        log.info("Starting select loop thread: {}.", name);
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop.doLoop();
                } catch (IOException e) {
                    notifyFailed(e);
                }
            }
        });

        th.setName(name);
        th.setDaemon(true);
        th.start();
        return th;
    }

    @Override
    protected void doStart() {
        try {
            startLoop(zebraLoop, "zebra-server-loop");
            notifyStarted();
            log.info("Select loop threads started correctly");
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        zebraLoop.shutdown();
        notifyStopped();
    }
}
