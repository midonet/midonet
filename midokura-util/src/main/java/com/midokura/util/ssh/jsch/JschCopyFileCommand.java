/**
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.util.ssh.jsch;

import com.midokura.util.ssh.SshScpFailedException;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 12/14/11
 */
public class JschCopyFileCommand extends JschCommand {

    public JschCopyFileCommand(String username, String hostname, int port,
                               UserInfo userInfo) {
        super(username, hostname, port, userInfo);
    }

    public boolean doCopy(String localFile, String remoteFile,
                         boolean directCopy, int timeout)
    {
        Session session = null;
        Channel channel = null;
        try
        {
            session = getJschSession(timeout);

            String command = "";

            if ( directCopy ) {
                command = "scp -p -t " + remoteFile;
            } else {
                command = "scp -f " + remoteFile;
            }

            channel = session.openChannel("exec");
            ((ChannelExec)channel).setCommand(command);

            if (directCopy)  {
                return copyLocalToRemote(localFile, channel);
            } else {
                return copyRemoteToLocal(localFile, channel);
            }

        } catch (Exception e) {
            throw new SshScpFailedException(String.format("Scp failed"), e);
        } finally {
            if ( channel != null ) {
                channel.disconnect();
            }

            if ( session != null ) {
                session.disconnect();
            }
        }
    }

    private boolean copyLocalToRemote(String localFileName,
                                      Channel channel)
        throws IOException, JSchException {
        // get I/O streams for remote scp
        OutputStream chanOut = channel.getOutputStream();
        InputStream chanIn = channel.getInputStream();

        channel.connect();

        if ( checkAck(chanIn) != 0 )
            return false;

        File localFile = new File(localFileName);

        // send "C0644 filesize filename", where filename should not include '/'
        String command =
            String.format("C0644 %d %s\n", localFile.length(), localFile.getName());
        sendString(chanOut, command);

        if ( checkAck(chanIn) != 0 )
            return false;

        // send a content of localFile
        FileInputStream fileInputStream = null;
        byte[] buf;
        try {
            fileInputStream = new FileInputStream(localFile);
            buf = new byte[1024];
            while(true) {
                int len=fileInputStream.read(buf, 0, buf.length);
                if( len <= 0 )
                    break;

                chanOut.write(buf, 0, len);
            }
        } finally {
            if ( fileInputStream != null) {
                fileInputStream.close();
            }
        }

        sendAck(chanOut);

        if (checkAck(chanIn) != 0) {
            return false;
        }

        return true;
    }

    private boolean copyRemoteToLocal(String localFile, Channel channel)
        throws IOException, JSchException {

        // get I/O streams for remote scp
        OutputStream chanOut = channel.getOutputStream();
        InputStream chanIn = channel.getInputStream();

        channel.connect();

        // send '\0'
        sendAck(chanOut);

        byte[] buf = new byte[1024];
        while(true){
            int c=checkAck(chanIn);
            if(c!='C'){
                break;
            }

            // read '0644 '
            if ( chanIn.read(buf, 0, 5) != 5 ) {
                return false;
            }

            long fileSize=0L;
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
            } while ( readByte != 0x0a && readByte != -1 );

            // send '\0'
            sendAck(chanOut);

            // read the content of the remote file and write to the local file
            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(localFile);
                int byteCount;
                while(true){
                    byteCount = buf.length < fileSize
                            ? buf.length : (int)fileSize;

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
                if ( fileOutputStream != null ) {
                    fileOutputStream.close();
                }
            }

            if(checkAck(chanIn)!=0){
               return false;
            }

            // send '\0'
            sendAck(chanOut);
        }

        return true;
    }
}
