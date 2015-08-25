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

package org.midonet.odp;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import org.midonet.util.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.Netlink;
import org.midonet.odp.protos.OvsDatapathConnection;
import org.midonet.util.eventloop.*;

public abstract class DatapathClient {

    private static final Logger log =
        LoggerFactory.getLogger(DatapathClient.class);

    public static OvsDatapathConnection createConnection() throws Exception {

        log.info("Creating the selector loop");
        final SelectLoop loop = new SimpleSelectLoop();

        log.info("Making the ovsConnection");
        final OvsDatapathConnection ovsConnection =
            OvsDatapathConnection.create(new Netlink.Address(0));

        ovsConnection.bypassSendQueue(true);

        log.info("Setting the channel to non blocking");
        ovsConnection.getChannel().configureBlocking(false);

        log.info("Registering the channel into the selector");
        loop.register(ovsConnection.getChannel(), SelectionKey.OP_READ,
                new SelectListener() {
                    @Override
                    public void handleEvent(SelectionKey key)
                            throws IOException {
                        ovsConnection.handleReadEvent(Bucket.BOTTOMLESS);
                    }
                }, SelectLoop.Priority.NORMAL);

        Thread loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    log.info("Entering loop");
                    loop.doLoop();
                } catch (IOException e) {
                    log.error("Error in io loop: {}", e);
                    System.exit(1);
                }
            }
        });

        log.info("Starting the selector loop");
        loopThread.start();

        return ovsConnection;
    }
}
