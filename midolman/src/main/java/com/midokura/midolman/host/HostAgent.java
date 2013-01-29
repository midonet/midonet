/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.host;

import com.google.common.base.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;

import com.midokura.midolman.guice.MonitoringStoreModule;
import com.midokura.midolman.guice.config.ConfigProviderModule;
import com.midokura.midolman.guice.datapath.DatapathModule;
import com.midokura.midolman.guice.reactor.ReactorModule;
import com.midokura.midolman.guice.zookeeper.ZookeeperConnectionModule;
import com.midokura.midolman.host.guice.HostAgentModule;
import com.midokura.midolman.host.services.HostAgentService;
import com.midokura.midolman.guice.cluster.ClusterClientModule;

import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.IOException;
import java.util.Properties;

/**
 * Main entry point for the Host Agent implementation. This class can start a
 * host agent in a standalone mode by providing a config file with connection
 * details.
 */
public class HostAgent {

    private final static Logger log =
            LoggerFactory.getLogger(HostAgent.class);

    private Injector injector;

    public void run(String[] args) throws IOException, ParseException {

        // log git commit info
        Properties properties = new Properties();
        properties.load(HostAgent.class.getClassLoader()
                .getResourceAsStream("git.properties"));
        log.info("host agent main start -------------------------");
        log.info("branch: {}", properties.get("git.branch"));
        log.info("commit.time: {}", properties.get("git.commit.time"));
        log.info("commit.id: {}", properties.get("git.commit.id"));
        log.info("commit.user: {}", properties.get("git.commit.user.name"));
        log.info("build.time: {}", properties.get("git.build.time"));
        log.info("build.user: {}", properties.get("git.build.user.name"));
        log.info("-------------------------------------");

        log.info("Added SIGTERM handling for cleanup");
        Signal.handle(new Signal("TERM"), new SignalHandler() {
            @Override
            public void handle(Signal sig) {
                doServiceCleanup();
            }
        });

        log.info("Adding shutdownHook");
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                doServiceCleanup();
            }
        });

        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");
        CommandLineParser parser = new GnuParser();
        CommandLine commandLine = parser.parse(options, args);

        String configFilePath =
                commandLine.getOptionValue('c', "./conf/midolman.conf");

        injector = Guice.createInjector(
                new ConfigProviderModule(configFilePath),
                new ZookeeperConnectionModule(),
                new DatapathModule(),
                new ReactorModule(),
                new HostAgentModule(),
                new MonitoringStoreModule(),
                new ClusterClientModule());

        injector.getInstance(HostAgentService.class).startAndWait();

        log.info("{} has started", HostAgentService.class);
    }

    private void doServiceCleanup() {
        HostAgentService instance =
                injector.getInstance(HostAgentService.class);

        if ( instance.state() == Service.State.TERMINATED )
            return;

        try {
            instance.stopAndWait();
        } catch (Exception e) {
            log.error("Exception ", e);
        } finally {
            log.info("Exiting. BYE (signal)!");
        }
    }

    public static void main(String[] args) {
        try {
            new HostAgent().run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }
}
