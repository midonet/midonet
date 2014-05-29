/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.tools;

import org.apache.commons.cli.*;
import org.midonet.util.jmx.JMXHelper;

import javax.management.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 * Created by tomoe on 2/24/14.
 */
public class MmStat {

    private static final Options options;
    private static final CommandLineParser parser = new PosixParser();

    private static final String DEFAULT_HOST = "localhost";
    private static final String DEFAULT_JMX_PORT = "7200";
    private static final String DEFAULT_UPDATE_COUNT = "1";
    private static final String DEFAULT_UPDATE_INTERVAL_SEC = "1";
    private static final String DEFAULT_JMX_DOMAIN =
            "org.midonet.midolman.monitoring.metrics";

    static {

        options = new Options();

        OptionBuilder.withLongOpt("interval");
        OptionBuilder.hasArg();
        OptionBuilder.withDescription("Interval in sec between updates");
        options.addOption(OptionBuilder.create("i"));

        OptionBuilder.withLongOpt("count");
        OptionBuilder.hasArg();
        OptionBuilder.withDescription(
                "Number of updates. defaults to 1");
        options.addOption(OptionBuilder.create("c"));

        OptionBuilder.withLongOpt("host");
        OptionBuilder.hasArg();
        OptionBuilder.withDescription("Midolman host to get JMX");
        options.addOption(OptionBuilder.create());


        OptionBuilder.withLongOpt("port");
        OptionBuilder.hasArg();
        OptionBuilder.withDescription("Midolman port to get JMX");
        options.addOption(OptionBuilder.create());

        OptionBuilder.withLongOpt("list-jmx-domains");
        OptionBuilder.withDescription("List JMX domains and exit");
        options.addOption(OptionBuilder.create());

        OptionBuilder.withLongOpt("help");
        OptionBuilder.withDescription("Print this help message");
        options.addOption(OptionBuilder.create());

        OptionBuilder.withLongOpt("set-jmx-domain");
        OptionBuilder.hasArg();
        OptionBuilder.withDescription(
                "Set a JMX domain to query. defaults to " + DEFAULT_JMX_DOMAIN);
        options.addOption(OptionBuilder.create());

        OptionBuilder.withLongOpt("filter");
        OptionBuilder.hasArg();
        OptionBuilder.withDescription("Filter on MBean's key properties");
        options.addOption(OptionBuilder.create());
    }

    private MBeanServerConnection mbsc = null;


    public MmStat(String host, String port) {
        String url = "service:jmx:rmi:///jndi/rmi://" + host + ":" + port +
                "/jmxrmi";
        try {
            mbsc = JMXHelper.newJmxServerConnectionFromUrl(url);
            System.out.println("Connected to JMX server: " + url);
        } catch (IOException e) {
            System.err.println("Error connecting to JMX server: " + url);
            e.printStackTrace();
            printHelp();
        }
    }

    public void listJmxDomains() throws IOException {

        System.out.println("==== Available JMX domains ====");
        for (String d : mbsc.getDomains()) {
            System.out.println(d);
        }
        System.exit(0);

    }

    public void dumpMBeans(String jmxDomain, String filter, int count,
                           int interval) {

        ObjectName on = null;
        try {
            on = new ObjectName(jmxDomain + ":*");
        } catch (MalformedObjectNameException e) {
            System.err.println("Aborted: malformed domain " + jmxDomain);
            e.printStackTrace();
            System.exit(1);
        }
        java.util.Set<ObjectInstance> beans = null;
        try {
            beans = mbsc.queryMBeans(on, null);
        } catch (IOException e) {
            System.err.println("Aborted: querying Mbeans failed " + e);
            e.printStackTrace();
        }

        // Remember MBean instances to dump
        List<ObjectInstance> objectInstances = new ArrayList<>();
        for (ObjectInstance oi : beans) {
            if (filter != null) {
                String objectName = oi.getObjectName().toString();
                if (!objectName.contains(filter)) {
                    continue;
                }
            }
            objectInstances.add(oi);
        }

        int attempt = 1;
        while (true) {
            // Header
            System.out.println("============================");
            System.out.println(
                    String.format("Reading at %s (%s/%s)",
                            new Date().toString(), attempt, count));
            System.out.println("============================");

            try {
                for (ObjectInstance oi : objectInstances) {
                    on = oi.getObjectName();
                    System.out.println("MBean: " + on.toString());
                    MBeanInfo info = mbsc.getMBeanInfo(on);

                    for (MBeanAttributeInfo attr : info.getAttributes()) {
                        System.out.print("\t" + attr.getName() + ": ");
                        System.out.println(mbsc.getAttribute(on, attr.getName()));
                    }
                }
                if (attempt == count) System.exit(0);
                attempt++;
                //* Sleep "interval" seconds" */
                Thread.sleep(interval * 1000);
            } catch (IOException e) {
                System.err.println("Aborted: got IOException: " + e);
                e.printStackTrace();
                System.exit(1);
            } catch (JMException e) {
                System.err.println("Aborted: got JMException: " + e);
                e.printStackTrace();
                System.exit(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        String syntax = "mm-stat";
        formatter.printHelp(syntax, options);

        String footer = "Examples:\n\n" +
                "To dump JMX MBeans of MidoNet Agent running locally: \n" +
                "  $ mm-stat \n\n" +

                "To dump JMX MBeans that have 'Histgram' in property list:\n" +
                "  $ mm-stat --filter Histgram\n\n" +

                "To list available JMX domains:\n" +
                "  $ mm-stat --list-jmx-domains\n\n" +

                "To set JMX domain and dump JMX attributes:\n" +
                "  $ mm-stat --set-jmx-domain me.prettyprint." +
                "cassandra.service_midonet \n\n" +

                "To dump JMX MBeans 10 times with 2-second interval:\n" +
                "  $ mm-stat -c 10 -i 2\n\n" +

                "To watch simulationLatency with 'watch' command:\n" +
                "  $ watch -d mm-stat --filter simulationLatency\n";

        System.out.println(footer);
    }

    public static void main(String[] args) throws ParseException, IOException {

        try {
            CommandLine cl = parser.parse(options, args);

            String host = cl.getOptionValue("host", DEFAULT_HOST);
            String port = cl.getOptionValue("port", DEFAULT_JMX_PORT);
            int count = Integer.parseInt(
                    cl.getOptionValue("count", DEFAULT_UPDATE_COUNT));
            int interval = Integer.parseInt(
                    cl.getOptionValue("interval", DEFAULT_UPDATE_INTERVAL_SEC));
            String jmxDomain = cl.getOptionValue("set-jmx-domain",
                    DEFAULT_JMX_DOMAIN);
            String filter = cl.getOptionValue("filter");

            MmStat mmStat = new MmStat(host, port);

            if (cl.hasOption("list-jmx-domains")) {
                mmStat.listJmxDomains();
                System.exit(0);
            } else if (cl.hasOption("help")) {
                printHelp();
                System.exit(0);
            }
            mmStat.dumpMBeans(jmxDomain, filter, count, interval);
        } catch (ParseException e) {
            System.err.println("Error parsing the command line: " + e.getMessage());
            printHelp();
            System.exit(1);
        }
    }
}