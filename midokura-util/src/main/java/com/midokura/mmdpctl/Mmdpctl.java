package com.midokura.mmdpctl;

import com.midokura.mmdpctl.commands.Command;
import com.midokura.mmdpctl.commands.DumpDatapathCommand;
import com.midokura.mmdpctl.commands.GetDatapathCommand;
import com.midokura.mmdpctl.commands.ListDatapathsCommand;
import com.midokura.mmdpctl.results.Result;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Mmdpctl {

    private static final Logger log = LoggerFactory
            .getLogger(Mmdpctl.class);

    private boolean showStats = false;
    private int timeout = 0;

    public void showStats() {
        this.showStats = true;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }


    private Mmdpctl() {}

    public int execute(Command<? extends Result> command) {

        // if timeout start counter.
        Future<? extends Result> resultFuture = command.execute();

        try {
            Result result = null;
            if (timeout > 0) {
                log.info("getting the result with a timeout of {} seconds", timeout);
                result = resultFuture.get(timeout, TimeUnit.SECONDS);
            } else {
                result = resultFuture.get();
            }

            // display result on screen.
            result.printResult();
        } catch (TimeoutException e) {
            System.out.println("Didn't get result in time. Aborting");
            resultFuture.cancel(true);
            return -1;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("Error while retrieving the datapaths: " + e.getMessage());
            return -1;
        }

        return 0;
    }


    public static void main(String ...args) {
        Options options = new Options();
        options.addOption("list", false, "List datapaths");
        options.addOption("show", true, " Show all the information related to a given datapath,\n" +
                "identified by its name");
        options.addOption("dump", true, "Show all the flows installed for a given datapath,\n" +
                "identified by its name.");
        options.addOption("s", "stats", false, "Show statistics about the given datapath.");
        options.addOption("t", "timeout", true, " Specifies a timeout in seconds. If the program is\n" +
                "not able to get the results in less than this amount of time it will\n" +
                "stop and return with an error code");


        CommandLineParser parser = new GnuParser();
        try {
            CommandLine cl = parser.parse(options, args);

            Mmdpctl mmdpctl = new Mmdpctl();

            if (!cl.hasOption("list") &&
                    !cl.hasOption("show") &&
                    !cl.hasOption("dump")) {
                showHelpAndExit(options, "Missing one of the required options ('list', 'show', 'dump')");
            }

            // check if the user requests the stats.
            if (cl.hasOption("stats")) {
                mmdpctl.showStats();
            }

            // check if the user sets a (correct) timeout.
            if (cl.hasOption("timeout")) {
                String timeoutString = cl.getOptionValue("timeout");
                Integer timeout = Integer.parseInt(timeoutString);
                if (timeout > 0) {
                    log.info("Installing a timeout of {} seconds", timeout);
                    mmdpctl.setTimeout(timeout);
                } else {
                    System.out.println("The timeout needs to be a positive number, bigger than 0.");
                    System.exit(-1);
                }
            }

            if (cl.hasOption("list")) {
                System.exit(mmdpctl.execute(new ListDatapathsCommand()));
            } else if (cl.hasOption("show")) {
                System.exit(mmdpctl.execute(new GetDatapathCommand(cl.getOptionValue("show"))));
            } else if (cl.hasOption("dump")) {
                System.exit(mmdpctl.execute(new DumpDatapathCommand(cl.getOptionValue("dump"))));
            }

            // get one of the commands.
        } catch (ParseException e) {
            showHelpAndExit(options, e.getMessage());
        }




    }

    private static void showHelpAndExit(Options options, String message) {
        System.out.println("Error with the options: "+ message);
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp( "mm-dpctl", options );
        System.exit(-1);
    }
}
