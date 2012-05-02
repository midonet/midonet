/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.util.ssh.commands;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import static java.lang.String.format;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 12/14/11
 */
public class CopyFileSshCommand {

    private SshSession session;

    public CopyFileSshCommand(SshSession session) {
        this.session = session;
    }

    public boolean doCopy(String localFile, String remoteFile,
                          boolean directCopy, int timeout)
        throws IOException {
        SshExecChannel channel = null;

        try {
            String command;

            if (directCopy) {
                command = "scp -p -t " + remoteFile;
            } else {
                command = "scp -f " + remoteFile;
            }

            channel = session.newExecChannel();
            channel.setCommand(command);

            if (directCopy) {
                return copyLocalToRemote(localFile, channel, timeout);
            } else {
                return copyRemoteToLocal(localFile, channel, timeout);
            }
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    private boolean copyLocalToRemote(String localFileName,
                                      SshExecChannel channel, int timeout)
        throws IOException {
        // get I/O streams for remote scp
        OutputStream chanOut = channel.getOutputStream();
        InputStream chanIn = channel.getInputStream();

        channel.connect(timeout);

        if (channel.checkAck(chanIn) != 0)
            return false;

        File localFile = new File(localFileName);

        // TODO: handle the filename permissions properly
        // send "C0644 filesize filename", where filename should not include '/'
        String command =
            format("C0644 %d %s\n", localFile.length(), localFile.getName());

        channel.sendString(chanOut, command);

        if (channel.checkAck(chanIn) != 0)
            return false;

        // send a content of localFile
        FileInputStream fileInputStream = null;
        byte[] buf;
        try {
            fileInputStream = new FileInputStream(localFile);
            buf = new byte[1024];
            while (true) {
                int len = fileInputStream.read(buf, 0, buf.length);
                if (len <= 0)
                    break;

                chanOut.write(buf, 0, len);
            }
        } finally {
            if (fileInputStream != null) {
                fileInputStream.close();
            }
        }

        channel.sendAck(chanOut);

        return channel.checkAck(chanIn) == 0;
    }

    @SuppressWarnings("ConstantConditions")
    private boolean copyRemoteToLocal(String localFile,
                                      SshExecChannel channel, int timeout)
        throws IOException {

        // get I/O streams for remote scp
        OutputStream chanOut = channel.getOutputStream();
        InputStream chanIn = channel.getInputStream();

        channel.connect(timeout);

        // send '\0'
        channel.sendAck(chanOut);

        byte[] buf = new byte[1024];
        while (true) {
            int c = channel.checkAck(chanIn);
            if (c != 'C') {
                break;
            }

            // read '0644 '
            if (chanIn.read(buf, 0, 5) != 5) {
                return false;
            }

            long fileSize = 0L;
            while (true) {
                if (chanIn.read(buf, 0, 1) < 0) {
                    // error
                    break;
                }
                if (buf[0] == ' ')
                    break;

                fileSize = fileSize * 10L + (long) (buf[0] - '0');
            }

            // read and ignore the name of the remote file
            byte readByte;
            do {
                readByte = (byte) chanIn.read();
            } while (readByte != 0x0a && readByte != -1);

            // send '\0'
            channel.sendAck(chanOut);

            // read the content of the remote file and write to the local file
            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(localFile);
                int byteCount;
                while (true) {
                    byteCount = buf.length < fileSize
                        ? buf.length : (int) fileSize;

                    byteCount = chanIn.read(buf, 0, byteCount);

                    if (byteCount < 0) {
                        break; // error
                    }

                    fileOutputStream.write(buf, 0, byteCount);
                    fileSize -= byteCount;
                    if (fileSize == 0L)
                        break; // final bit
                }

                fileOutputStream.close();
            } finally {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
            }

            if (channel.checkAck(chanIn) != 0) {
                return false;
            }

            // send '\0'
            channel.sendAck(chanOut);
        }

        return true;
    }
}
