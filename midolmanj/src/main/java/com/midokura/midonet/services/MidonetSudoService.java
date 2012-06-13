/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.services;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.services.privsep.MidonetPrivSepServer;
import com.midokura.remote.RemoteHost;
import com.midokura.tools.timed.Timed;
import com.midokura.util.process.ProcessHelper;

/**
 * Wrapper to handle the lifecycle of the midonet-privsep binary.
 * It provides convenient access to the client interface and it will also
 * properly start the service when required.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/25/12
 */
public class MidonetSudoService {

    private static MidonetPrivSepServer.Iface clientInterface;
    private static Process sudoHelperProcess;

    private static String midonetHelperServiceBinary = "midonet-privsep";

    private static final Logger log = LoggerFactory
        .getLogger(MidonetSudoService.class);

    public static synchronized MidonetPrivSepServer.Iface getClient()
        throws Exception {

        if (sudoHelperProcess == null || clientInterface == null) {
            startHelperService();

            // it seems that if we try to quickly to connect to a port that is
            // forwarded and nothing else is available on the other side it will
            // be marked as closed and every other subsequent connection will
            // fail. So we wait a little here before trying to connect. This is
            // a hack but it beats looking into the jsch sources for the root
            // cause.
            Thread.sleep(100);

            Timed.ExecutionResult<MidonetPrivSepServer.Client> clientResult =
                Timed.newTimedExecution()
                     .until(TimeUnit.SECONDS.toMillis(20))
                     .waiting(TimeUnit.MILLISECONDS.toMillis(250))
                     .execute(
                         new Timed.Execution<MidonetPrivSepServer.Client>() {
                             @Override
                             protected void _runOnce() throws Exception {
                                 try {
                                     log.debug("Pinging privilege separation ");

                                     TTransport transport =
                                         new TSocket("localhost", 9090);

                                     transport.open();

                                     TProtocol proto = new TBinaryProtocol(
                                         transport);

                                     MidonetPrivSepServer.Client client =
                                         new MidonetPrivSepServer.Client(proto);

                                     String pingResult = client.ping("test");

                                     setResult(client);
                                     setCompleted("test".equals(pingResult));
                                 } catch (TTransportException e) {
                                     // catch exceptions so we can try again later.
                                 }
                             }
                         });

            if (!clientResult.completed()) {
                throw new IOException("midonet privilege separation " +
                                          "daemon didn't launch properly.");
            }

            clientInterface = clientResult.result();
        }

        return clientInterface;
    }

    private static void startHelperService() {
        RemoteHost remoteHostSpec = RemoteHost.getSpecification();

        if (remoteHostSpec.isValid()) {
            midonetHelperServiceBinary =
                remoteHostSpec.getMidonetHelperPath(midonetHelperServiceBinary);
        }

        ProcessHelper.RunnerConfiguration daemonRunConfig =
            ProcessHelper.newDemonProcess(midonetHelperServiceBinary)
                         .withSudo();

        if (log.isDebugEnabled()) {
            daemonRunConfig.logOutput(log, "<midonet-privsep>",
                                      ProcessHelper.OutputStreams.StdError,
                                      ProcessHelper.OutputStreams.StdOutput);
        }

        sudoHelperProcess = daemonRunConfig.run();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                ProcessHelper.killProcess(sudoHelperProcess);
            }
        });
    }
}
