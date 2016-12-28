/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.util

import com.sun.jna.LastErrorException

import org.midonet.jna.Ethtool._
import org.midonet.jna.If._
import org.midonet.jna.{CLibrary, Socket, Sockios}

object EthtoolOps {

    /**
      * Retrieves the settings of the specified network interface.
      */
    @throws[LastErrorException]
    def getSettings(interfaceName: String): EthtoolCmd = {
        require(interfaceName.length < 16, s"interface name exceeds $IFNAMSIZ")

        val fd = CLibrary.socket(Socket.AF_INET, CLibrary.SOCK_DGRAM, 0)
        try {
            val cmd = new EthtoolCmd
            cmd.cmd = ETHTOOL_GSET
            cmd.write()

            val ifr = new IfReq
            ifr.ifrIfrn.setName(interfaceName)
            ifr.ifrIfru.setData(cmd.getPointer)
            ifr.write()

            CLibrary.ioctl(fd, Sockios.SIOCETHTOOL, ifr.getPointer)

            cmd.read()
            cmd
        } finally {
            CLibrary.close(fd)
        }
    }

    /**
      * Gets the state of the TX checksum offloading for the given interface.
      * @return True if TX checksum offloading is enabled, false otherwise.
      */
    @throws[LastErrorException]
    def getTxChecksum(interfaceName: String): Boolean = {
        valueOp(interfaceName, ETHTOOL_GTXCSUM, 0) != 0
    }

    /**
      * Enables or disables the TX checksum offloading on the given interface
      * and returns its current status.
      */
    @throws[LastErrorException]
    def setTxChecksum(interfaceName: String, enabled: Boolean): Boolean = {
        valueOp(interfaceName, ETHTOOL_STXCSUM, if (enabled) 1 else 0) != 0
    }

    /**
      * Gets the state of the RX checksum offloading for the given interface.
      * @return True if RX checksum offloading is enabled, false otherwise.
      */
    @throws[LastErrorException]
    def getRxChecksum(interfaceName: String): Boolean = {
        valueOp(interfaceName, ETHTOOL_GRXCSUM, 0) != 0
    }

    /**
      * Enables or disables the RX checksum offloading on the given interface
      * and returns its current status.
      */
    @throws[LastErrorException]
    def setRxChecksum(interfaceName: String, enabled: Boolean): Boolean = {
        valueOp(interfaceName, ETHTOOL_SRXCSUM, if (enabled) 1 else 0) != 0
    }

    @throws[LastErrorException]
    private def valueOp(interfaceName: String, cmd: Int, data: Int): Int = {
        require(interfaceName.length < 16, s"interface name exceeds $IFNAMSIZ")

        val fd = CLibrary.socket(Socket.AF_INET, CLibrary.SOCK_DGRAM, 0)
        try {
            val value = new EthtoolValue
            value.cmd = cmd
            value.data = data
            value.write()

            val ifr = new IfReq
            ifr.ifrIfrn.setName(interfaceName)
            ifr.ifrIfru.setData(value.getPointer)
            ifr.write()

            CLibrary.ioctl(fd, Sockios.SIOCETHTOOL, ifr.getPointer)

            value.read()
            value.data
        } finally {
            CLibrary.close(fd)
        }
    }

}
