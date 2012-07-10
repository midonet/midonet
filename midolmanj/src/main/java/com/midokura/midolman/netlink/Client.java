/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.netlink;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.util.netlink.Netlink;
import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.netlink.NetlinkSelectorProvider;
import com.midokura.util.netlink.dp.Datapath;
import com.midokura.util.netlink.protos.OvsDatapathConnection;
import com.midokura.util.reactor.Reactor;
import static com.midokura.util.netlink.Netlink.Protocol;

public class Client {

    private static final Logger log = LoggerFactory
        .getLogger(Client.class);

    public static void main(String[] args) throws Exception {

        SelectorProvider provider = SelectorProvider.provider();

        if (!(provider instanceof NetlinkSelectorProvider)) {
            log.error("Invalid selector type: {}", provider.getClass());
            return;
        }

        NetlinkSelectorProvider netlinkSelector = (NetlinkSelectorProvider) provider;

        final NetlinkChannel netlinkChannel =
            netlinkSelector.openNetlinkSocketChannel(Protocol.NETLINK_GENERIC);

        log.info("Connecting");
        netlinkChannel.connect(new Netlink.Address(0));

        log.info("Creating the selector loop");
        final SelectLoop loop = new SelectLoop(
            Executors.newScheduledThreadPool(1));

        log.info("Making the ovsConnection");
        final OvsDatapathConnection ovsConnection =
            OvsDatapathConnection.create(netlinkChannel, new Reactor() {
                @Override
                public long currentTimeMillis() {
                    return loop.currentTimeMillis();
                }

                @Override
                public <V> Future<V> submit(final Callable<V> work) {
                    //noinspection unchecked
                    return (Future<V>)loop.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                work.call();
                            } catch (Exception e) {
                                log.error("Exception");
                            }
                        }
                    });
                }

                @Override
                public <V> ScheduledFuture<V> schedule(long delay, TimeUnit unit, final Callable<V> work) {
                    //noinspection unchecked
                    return (ScheduledFuture<V>)loop.schedule(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                work.call();
                            } catch (Exception e) {
                                log.error("Exception");
                            }
                        }
                    }, delay, unit);
                }
            });

        log.info("Setting the channel to non blocking");
        netlinkChannel.configureBlocking(false);

        log.info("Registering the channel into the selector");
        loop.register(netlinkChannel, SelectionKey.OP_READ,
                      new SelectListener() {
                          @Override
                          public void handleEvent(SelectionKey key)
                              throws IOException {
                              ovsConnection.handleEvent(key);
                          }
                      });

        Thread loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    log.info("Entering loop");
                    loop.doLoop();
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
            }
        });

        log.info("Starting the selector loop");
        loopThread.start();

        log.info("Initializing ovs connection");
        ovsConnection.initialize();

        while ( ! ovsConnection.isInitialized() ) {
            Thread.sleep(TimeUnit.MILLISECONDS.toMillis(50));
        }

//        log.info("datpaths {}", ovsConnection.datapathsEnumerate().get());
        log.info("Deleting wrong datapath");
        Future<Datapath> datapathFuture = ovsConnection.datapathsDelete("test3");

        try {
            log.info("Created datapath: {}.", datapathFuture.get());
        } catch (ExecutionException e) {
            log.info("Exception while deleting datapath");
        }

        log.info("Deleting good datapath {}", ovsConnection.datapathsDelete("test").get());
    }
}
