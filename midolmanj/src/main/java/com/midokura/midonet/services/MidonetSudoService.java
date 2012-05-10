/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.services;

import java.util.concurrent.TimeUnit;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

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

    public static synchronized MidonetPrivSepServer.Iface getClient()
        throws Exception {

        if (sudoHelperProcess == null || clientInterface == null) {
            startHelperService();

            Thread.sleep(5000);

            final TTransport transport = new TSocket("localhost", 9090);

            Timed.ExecutionResult<MidonetPrivSepServer.Client> clientResult =
                Timed.newTimedExecution()
                     .until(TimeUnit.SECONDS.toMillis(20))
                     .waiting(TimeUnit.MILLISECONDS.toMillis(250))
                     .execute(new Timed.Execution<MidonetPrivSepServer.Client>() {
                         @Override
                         protected void _runOnce() throws Exception {
                             try {
                                 transport.open();

                                 TProtocol proto = new TBinaryProtocol(transport);

                                 setResult(new MidonetPrivSepServer.Client(proto));
                                 setCompleted("test".equals(getResult().ping("test")));
                             } catch (TTransportException e) {
                                 // catch exceptions so we can try again later.
                             }
                         }
                     });

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

        sudoHelperProcess =
            ProcessHelper
                .newDemonProcess(midonetHelperServiceBinary)
                .withSudo()
                .run();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                ProcessHelper.killProcess(sudoHelperProcess);
            }
        });
    }
}
