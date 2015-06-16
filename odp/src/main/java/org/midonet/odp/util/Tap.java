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
package org.midonet.odp.util;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.Union;

public class Tap {

    private final static Logger log = LoggerFactory.getLogger(Tap.class);

    /* if.h */
    public static final int IFNAMSIZ = 16;

    /* if_tun.h */
    public static final int TUNSETIFF = 0x400454ca;
    public static final int TUNSETPERSIST = 0x400454cb;

    public static final int TUNSETOWNER = 0x400454cc;
    public static final int TUNSETGROUP = 0x400454ce;

    public static final int IFF_TUN = 0x0001;
    public static final int IFF_TAP = 0x0002;
    public static final int IFF_NO_PI = 0x1000;

    /* ioctls.h */
    public static final int SIOCGIFHWADDR = 0x8927;        /* Get hardware address */
    public static final int SIOCSIFHWADDR = 0x8924;        /* set hardware address */

    /* fcntl.h */
    public static final int O_RDONLY = 0;
    public static final int O_WRONLY = 1;
    public static final int O_RDWR = 2;
    public static final int O_NONBLOCK = 04000;
    public static final int EWOULDBLOCK = 11;


    public static class OpenTapDescriptor {
        public String name;
        public int fd;
    }

    /**
     * Open a TAP interface.
     *
     * @param tapName   The name of the interface to create, e.g. 'tap0'.
     *                  If empty the name of the tap created will be copied.
     *
     * @param nonblocking If we want to open the interface in non-blocking mode.
     *
     *
     * @return  The file descriptor of the open socket to the TAP interface.
     */
    public static OpenTapDescriptor openTap(String tapName, boolean nonblocking) {
        // check if the the parameter is valid
        if (tapName != null && tapName.length() > IFNAMSIZ) {
            throw new RuntimeException("Tap name too long!");
        }

        String tunPath = "/dev/net/tun";
        int flags = nonblocking ? O_NONBLOCK : 0;
        int fd = NativeTapLibrary.INSTANCE.open(tunPath, flags | O_RDWR);

        if (fd < 0)
            throw new RuntimeException(
                String.format("Error while opening /dev/net/tun: %s", _errmsg()));

        InterfaceReq interfaceReq = new InterfaceReq();
        interfaceReq.ifr_ifru.setType("ifru_flags");
        interfaceReq.ifr_ifrn.setType("ifrn_name");
        interfaceReq.ifr_ifru.ifru_flags = IFF_TAP | IFF_NO_PI;

        if (tapName != null && !tapName.isEmpty())
            interfaceReq.ifr_ifrn.ifrn_name = tapName.getBytes();
        int result = NativeTapLibrary.INSTANCE.ioctl(fd, TUNSETIFF,
                                                     interfaceReq);
        // check if ioctl has been successful
        if (result < 0) {
            String errorMessage = _errmsg();
            closeFD(fd);
            throw new RuntimeException(
                String.format(
                    "Error doing ioctl on the tap %s. Error message: %s",
                    tapName, errorMessage));
        }

        OpenTapDescriptor descriptor = new OpenTapDescriptor();
        descriptor.fd = fd;
        descriptor.name = tapName;

        /* get the name */
        if (tapName == null || tapName.isEmpty()) {
            descriptor.name = new String(interfaceReq.ifr_ifrn.ifrn_name);
        }

        return descriptor;

        /* ALTERNATIVE
         NativeLibrary unix = NativeLibrary.getInstance("c");
       Function ioctl = unix.getFunction("ioctl");

       int res2 = ioctl.invokeInt(new Object[]{fd, 0x400454ca, interfaceReq});
        */
    }

    private static String _errmsg() {
        return _errmsg(Native.getLastError());
    }

    private static String _errmsg(int errorCode) {
        return NativeTapLibrary.INSTANCE.strerror(errorCode);
    }

    public static void openPersistentTapSetOwner(String tapName, int owner) {
        openPersistentTap(tapName, owner, -1, "");
    }

    public static void openPersistentTapSetGroup(String tapName, int group) {
        openPersistentTap(tapName, -1, group, "");
    }

    public static void openPersistentTap(String tapName) {
        openPersistentTap(tapName, -1, -1, "");
    }

    public static void openPersistentTap(String tapName, String hwAddr) {
        openPersistentTap(tapName, -1, -1, hwAddr);
    }

    /**
     *  Create or setup a persistent TAP interface.
     *
     *  If the interface already exists, it is setup with the given owner and group
     *  and set persistent.
     *
     *  Creating a new interface requires running as user root, or with Linux
     *  capability CAP_NET_ADMIN.
     *
     *  @param tapName If not empty or null, the name of the interface to create,
     *                 e.g. 'tap0'.
     *
     *  @param owner The UID (user id) of the user owning the interface, as an
     *               integer value. If < 0, the interface has no owner and is usable
     *               only by root.
     *
     *  @param group The GID (group id) of the group owning the interface, as an
     *               integer value. If < 0, the interface has no owner and is
     *               usable only by root.
     *
     *  @param hwAddr The MAC address of the interface, as a string in the form
     *                '01:23:45:67:89:ab'. If empty or null, an address is
     *                randomly chosen by the OS.
     *
     *  @throws RuntimeException If the interface cannot be created or setup
     *  or if user/group cannot be set.
     *
     */
    public static void openPersistentTap(String tapName, int owner, int group, String hwAddr) {
        int res;
        int fd = openTap(tapName, false).fd;

        /* change owner */
        if (owner >= 0) {
            res = NativeTapLibrary.INSTANCE.ioctl(fd, TUNSETOWNER, owner);
            if (res < 0) {
                closeFD(fd);
                throw new RuntimeException("ioctl failed in setting the owner!");
            }
        }

        /* change group */
        if (group >= 0) {
            res = NativeTapLibrary.INSTANCE.ioctl(fd, TUNSETGROUP, group);
            if (res < 0) {
                closeFD(fd);
                throw new RuntimeException("ioctl failed in setting the group!");
            }
        }

        /* make it persistent */
        res = NativeTapLibrary.INSTANCE.ioctl(fd, TUNSETPERSIST, 1);
        if (res < 0) {
            closeFD(fd);
            throw new RuntimeException("ioctl failed in making the tap persistent!");
        }

        /* change hw address */
        if (hwAddr != null && !hwAddr.isEmpty()) {
            setHwAddress(fd, tapName, hwAddr);
        }

        closeFD(fd);

    }

    /**
     * Set the hardware address of a network interface.
     *
     * @param fd opened file descriptor for the tap device.
     *
     * @param tapName name of the network interface, e.g. 'eth0'.
     *
     * @param hwAddress address to set in the form '01:23:45:67:89:ab'
     *
     * @throws RuntimeException If the some parameter is invalid or if the hw
     * address cannot be changed.
     *
     */
    public static void setHwAddress(int fd, String tapName, String hwAddress) {

        if (fd < 0 ||
            tapName == null || tapName.isEmpty() ||
            hwAddress == null || hwAddress.isEmpty())
            throw new RuntimeException("Invalid Parameter!");

        /* Prepare structure ifr_ifru */
        InterfaceReq interfaceReq = new InterfaceReq();
        interfaceReq.ifr_ifru.setType("ifru_hwaddr");
        interfaceReq.ifr_ifrn.setType("ifrn_name");

        /*  TapName */
        interfaceReq.ifr_ifrn.ifrn_name = tapName.getBytes();

        /* HW address */
        String[] bytes = hwAddress.split(":");
        // byte[] parsed = new byte[bytes.length];
        if (bytes.length != 6)
            throw new RuntimeException("Invalid MAC address!");

        for (int x = 0; x < bytes.length; x++) {
            Integer value = Integer.parseInt(bytes[x], 16);
            /* get just the first byte */
            byte bValue = value.byteValue();
            interfaceReq.ifr_ifru.ifru_hwaddr.sa_data[x] = bValue;
        }

        /* copy hw address family */
        InterfaceReq hwStruct = new InterfaceReq();
        getHwAddress(fd, tapName, hwStruct);
        interfaceReq.ifr_ifru.ifru_hwaddr.sa_family_t = hwStruct.ifr_ifru.ifru_hwaddr.sa_family_t;

        /* change MAC */
        int res = NativeTapLibrary.INSTANCE.ioctl(fd, SIOCSIFHWADDR, interfaceReq);
        if (res < 0)
            throw new RuntimeException(String.format(
                "ioctl failed in changing the MAC address: %s!", _errmsg()));
    }

    /**
     * Get the hardware address of a network interface.
     *
     * @param tapName The name of the network interface, e.g. 'eth0'.
     *
     * @return The MAC address of the interface, in the form '01:23:45:67:89:ab'.
     */
    public static String getHwAddress(String tapName) {
        int fd = openTap(tapName, false).fd;
        InterfaceReq hwStruct = new InterfaceReq();
        String hwAddr = getHwAddress(fd, tapName, hwStruct);
        NativeTapLibrary.INSTANCE.close(fd);
        return hwAddr;
    }

    /**
     * Get the hardware address of a network interface.
     *
     * @param tapName The name of the network interface, e.g. 'eth0'.
     *
     * @param fd The fd of the open tap device.
     *
     * @return The MAC address of the interface, in the form '01:23:45:67:89:ab'.
     */
    public static String getHwAddress(String tapName, int fd) {
        InterfaceReq hwStruct = new InterfaceReq();
        return getHwAddress(fd, tapName, hwStruct);
    }

    /**
     * Get the hardware address of a network interface.
     *
     * @param fd The fd of the open tap device.
     *
     * @param tapName The name of the network interface, e.g. 'eth0'.
     *
     * @param hardwareStructure structure filled by ioctl call
     *
     * @return The MAC address of the interface, in the form '01:23:45:67:89:ab'.
     */
    protected static String getHwAddress(int fd, String tapName,
                                         InterfaceReq hardwareStructure) {

        if (fd < 0 || tapName == null || tapName.isEmpty() || hardwareStructure == null )
            throw new RuntimeException("Invalid parameter!");

        /* clear structure */
        hardwareStructure.clear();

        hardwareStructure.ifr_ifru.setType("ifru_hwaddr");
        hardwareStructure.ifr_ifrn.setType("ifrn_name");

        /*  TapName */
        hardwareStructure.ifr_ifrn.ifrn_name = tapName.getBytes();

        int res = NativeTapLibrary.INSTANCE.ioctl(fd, SIOCGIFHWADDR, hardwareStructure);
        if (res < 0)
            throw new RuntimeException(
                String.format("ioctl failed in getting the MAC address: %s!", _errmsg()));

        /* Format String */
        StringBuilder sb = new StringBuilder(18);
        /* only the first 6 bytes are valid, the rest are 0s */
        for (int i = 0; i < 6; i++) {
            byte b = hardwareStructure.ifr_ifru.ifru_hwaddr.sa_data[i];
            if (sb.length() > 0)
                sb.append(':');
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    /**
     * Destroys a persistent TAP interface.
     *
     * Make the interface with the given name non-persistent, which in effect
     * destroys it.
     *
     * @param name The name of the interface to destroy.
     *
     * @throws RuntimeException if the interface cannot be destroyed, e.g.
     * it doesn't exist.
     */
    public static void destroyPersistentTap(String name) {
        int fd = openTap(name, false).fd;
        try {
            int res = NativeTapLibrary.INSTANCE.ioctl(fd, TUNSETPERSIST, 0);
            if (res < 0)
                throw new RuntimeException(
                    String.format(
                        "ioctl failed while destroying the tap with error: %s!",
                        _errmsg()));
        } finally {
            NativeTapLibrary.INSTANCE.close(fd);
        }
    }

    /**
     * It will close the passed in file descriptor.
     *
     * @param fd the file descriptor to close.
     *
     */
    public static void closeFD(int fd) {
        Native.setLastError(0);
        int res = NativeTapLibrary.INSTANCE.close(fd);
        if (res < 0) {
            log.error(
                "Closing write fd returned {} and last error is {}", res, _errmsg());
        }
    }

    /**
     * Will try to write to the tap device named by name.
     *
     * @param name is the name of the device we want to write
     *
     * @param buffer is the buffer from which we want to write
     *
     * @param nBytes are the number of bytes we want to write
     */
    public static void writeToTap(String name, byte[] buffer, int nBytes) {
        int fd = openTap(name, false).fd;

        try {
            writeToTap(fd, buffer, nBytes);
        } catch (Exception e) {
            closeFD(fd);
            e.printStackTrace();
        }
        closeFD(fd);
    }

    /**
     * Will try to write to the fd passed in. This should normally be a fd
     * obtained by opening a tap device.
     *
     * @param fd is the fd of the device we want to write to.
     *
     * @param buffer is the buffer from which we want to write.
     *
     * @param nBytes are the number of bytes we want to write.
     */
    public static void writeToTap(int fd, byte[] buffer, int nBytes) {
        long maxSleepMillis = 1000;
        long timeSlept = 0;
        while (nBytes > 0) {
            Native.setLastError(0);
            int res = NativeTapLibrary.INSTANCE.write(fd, buffer, nBytes);
            int err = Native.getLastError();
            if (res < 0) {
                if (err != EWOULDBLOCK)
                    throw new RuntimeException(
                        String.format("write failed with error %s!",
                                      _errmsg(err)));
            }
            if (res > 0) {
                nBytes -= res;
                if (0 == nBytes)
                    return;
                buffer = Arrays.copyOfRange(buffer, res, nBytes);
            }
            if (timeSlept >= maxSleepMillis)
                throw new RuntimeException("write timed out");
            try {
                log.debug("Sleeping for 100 millis.");
                Thread.sleep(100);
            } catch (InterruptedException e) {
                log.error("InterruptedException while reading from tap device", e);
            }
            timeSlept += 100;
        }
    }

    /**
     * Will try to read some data from the tap device.
     *
     * @param name the name of the tap device we want to read from
     *
     * @param buffer the target buffer onto we wish to read
     *
     * @param nBytes the number of bytes we want to read
     *
     * @return the number of bytes actually read
     */
    public static int readFromTap(String name, byte[] buffer, int nBytes) {

        int fd = openTap(name, true).fd;
        int res = -1;
        try {
            res = readFromTap(fd, buffer, nBytes);
        } catch (Exception e) {
            closeFD(fd);
            e.printStackTrace();
        }
        closeFD(fd);
        return res;
    }

    /**
     * Will try to read some data from the tap device (opened as a fd).
     *
     * @param fd the file description of an open tap device we want to read from
     *
     * @param buffer the target buffer onto we wish to read
     *
     * @param nBytes the number of bytes we want to read
     *
     * @return the number of bytes actually read
     */
    public static int readFromTap(int fd, byte[] buffer, int nBytes) {
        //log.debug("Opened tap {} non-blocking returned fd:{}", name, fd);
        Native.setLastError(0);
        int res = NativeTapLibrary.INSTANCE.read(fd, buffer, nBytes);
        int err = Native.getLastError();
        if (res < 0) {
            if (err != EWOULDBLOCK)
                throw new RuntimeException(
                    String.format("read failed with error %s!", _errmsg(err)));
            return 0;
        }
        return res;
    }

    public static class InterfaceReq extends Structure {

        public InterfaceReqName ifr_ifrn;
        public InterfaceReqParam ifr_ifru;

        public static class InterfaceReqByRef extends InterfaceReq implements Structure.ByReference {
        }

        public static class InterfaceReqName extends Union {
            public byte[] ifrn_name = new byte[IFNAMSIZ];

            public static class ByReference extends Union implements Structure.ByReference {
            }
        }

        public static class InterfaceReqParam extends Union {
            public SockAddr ifru_hwaddr;
            public SockAddr ifru_addr;
            public SockAddr ifru_dstaddr;
            public SockAddr ifru_broadaddr;
            public SockAddr ifru_netmask;
            public short ifru_flags;
            public int ifru_ivalue;
            public int ifru_mtu;
            public byte[] ifru_slave = new byte[IFNAMSIZ];
            public byte[] ifru_newname = new byte[IFNAMSIZ];

            public static class ByReference extends Union implements Structure.ByReference {
            }
        }

        public static class SockAddr extends Structure {
            public short sa_family_t;
            public byte[] sa_data = new byte[14];
        }
    }

    public interface NativeTapLibrary extends Library {

        NativeTapLibrary INSTANCE = (NativeTapLibrary)
            Native.loadLibrary("c", NativeTapLibrary.class);

        int ioctl(int fd, int code, InterfaceReq req);

        int ioctl(int fd, int code, int owner);

        int open(String name, int flags);

        int close(int fd);

        int fcntl(int fd, int cmd);

        int read(int handle, byte[] buffer, int nbyte);

        int write(int handle, byte[] buffer, int nbyte);

        String strerror(int errorCode);
    }

}
