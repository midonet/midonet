/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.spi.SelectorProvider;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.DataDumper;

/**
 * Debugging wrapper for a {@link NetlinkChannelImpl} that will dump the
 * protocol buffers into a file to ensure easy creation of test cases that
 * exercise the upper protocol layers without the need to run them on a Linux
 * box.
 */
public class NetlinkTracingChannel extends NetlinkChannelImpl {

    private static final Logger log =
        LoggerFactory.getLogger(NetlinkTracingChannel.class);

    String pid = ManagementFactory.getRuntimeMXBean().getName();

    File dumpFile;

    public NetlinkTracingChannel(SelectorProvider provider,
                                 NetlinkProtocol protocol) {
        super(provider, protocol);

        try {
            dumpFile = File.createTempFile("midonet-dump", pid);
        } catch (IOException e) {
            log.error("Could not open tracing file in /tmp", e);
        }
    }

    @Override
    public int write(ByteBuffer buffer) throws IOException {
        int written = super.write(buffer);

        if (dumpFile != null && dumpFile.isFile() && dumpFile.canWrite() ){
            String data = DataDumper.dumpAsByteArrayDeclaration(buffer.array(), 0, written);
            FileUtils.writeStringToFile(dumpFile,
                                        "/*\n// write - time: " +
                                            System.currentTimeMillis() +
                                            "\n    " + data + ",\n*/", true);
        }

        return written;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        int read = super.read(dst);

        if (dumpFile != null && dumpFile.isFile() && dumpFile.canWrite() ){
            String data = DataDumper.dumpAsByteArrayDeclaration(dst.array(), 0, read);
            FileUtils.writeStringToFile(dumpFile,
                                        "\n\t// read - time: " +
                                            System.currentTimeMillis() +
                                            "\n    " + data + ",\n", true);
        }

        return read;
    }
}
