/**
 * VpnPortAgent.scala - VPN port agent classes.
 *
 * This is file implements agent classes for VPN.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice

import com.midokura.midolman.state.{StateAccessException,
                                    StatePathExistsException, VpnZkManager,
                                    ZkManager}
import com.midokura.midolman.state.VpnZkManager.{VpnConfig, VpnType}

import scala.collection.JavaConversions._
import scala.collection.mutable

import java.io.IOException
import java.util.EnumMap
import java.util.UUID

import org.slf4j.LoggerFactory


object VpnPortAgent {
    private final val log = LoggerFactory.getLogger(this.getClass)
}

/**
 * VPN port agent implementation.
 */
class VpnPortAgent(val sessionId: Long, val datapathId: Long,
                   val vpnMgr: VpnZkManager) {
    import VpnPortAgent._

    var running = false
    val vpnIdToConfig = mutable.Map[UUID, VpnConfig]()
    val vpnTypeToService = new EnumMap[VpnType, PortService](classOf[VpnType])

    def this(sessionId: Long, datapathId: Long, vpnMgr: VpnZkManager,
             vpnType: VpnType, vpnService: PortService) {
        this(sessionId, datapathId, vpnMgr)
        vpnTypeToService(vpnType) = vpnService
    }

    def setPortService(vpnType: VpnType, vpnService: PortService) {
        log.debug("setPortService")
        if (!vpnTypeToService.contains(vpnType)) {
            vpnTypeToService(vpnType) = vpnService
        } else {
            log.warn("VPN service found for type {}", vpnType.toString)
        }
    }

    def delPortService(vpnType: VpnType) {
        log.debug("delPortService")
        if (vpnTypeToService.contains(vpnType)) {
            vpnTypeToService -= vpnType
        } else {
            log.warn("VPN service not found for type {}", vpnType.toString)
        }
    }

    def getPorts(): Set[UUID] = {
        return (for ((vpnId, vpn) <- vpnIdToConfig)
                yield Set(vpn.publicPortId, vpn.privatePortId)).toSet.flatten
    }

    def getVpn(): Set[UUID] = {
        return vpnIdToConfig.keySet.toSet
    }

    def addVpn(vpnId: UUID, vpn: VpnConfig): Boolean = {
        log.debug("addVpn: vpnId {}", vpnId)

        if (!vpnTypeToService.contains(vpn.vpnType)) {
            log.warn("No vpn service found for vpn {} type {}",
                     vpnId, vpn.vpnType.toString)
            return false
        }

        // Lock the VPN.
        try {
            vpnMgr.lock(vpnId, sessionId)
         } catch {
            case e: StatePathExistsException => {
                log.info("vpn {} already served by session_id {}",
                         vpnId, sessionId)
                return false
            }
        }

        val service = vpnTypeToService(vpn.vpnType)
        try {
            service.addPort(datapathId, vpn.publicPortId)
            service.configurePort(vpn.publicPortId)
            service.addPort(datapathId, vpn.privatePortId)
            service.start(vpnId)
        } catch {
            case e: Exception => {
                log.error("Couldn't start VPN port service", e)
                vpnMgr.unlock(vpnId)
                throw e
            }
        }
        log.info("Add vpn {} type {}", vpnId, vpn.vpnType.toString)
        vpnIdToConfig(vpnId) = vpn

        return true
    }

    def delVpn(vpnId: UUID) {
        log.debug("delVpn: vpnId {}", vpnId)

        val vpn = vpnIdToConfig(vpnId)
        if (!vpnTypeToService.contains(vpn.vpnType)) {
            log.warn("No vpn service found for vpn {} type {}",
                     vpnId, vpn.vpnType.toString)
            throw new RuntimeException("Cannot delete vpn")
        }

        log.info("delete vpn {} type {}", vpnId, vpn.vpnType.toString)
        val service = vpnTypeToService(vpn.vpnType)
        service.stop(vpnId)
        service.delPort(vpn.publicPortId)
        service.delPort(vpn.privatePortId)
        try {
            vpnMgr.unlock(vpnId)
        } catch {
            case e: StateAccessException => {
                log.warn("delVpn: unlock failed")
            }
        }
        vpnIdToConfig -= vpnId
    }

    private def handleVpn(vpns: Map[UUID, VpnConfig]) {
        log.debug("handleVpn")

        // Try adding newly created or unlocked VPN.
        for {
            (vpnId, vpn) <- vpns
            if !vpnIdToConfig.contains(vpnId)
        } {
            try {
                if (!addVpn(vpnId, vpn)) {
                    vpnMgr.wait(vpnId, new Runnable {
                        override def run() {
                            if (running) {
                                try {
                                    log.debug("watcher: addVpn")
                                    addVpn(vpnId, vpn)
                                } catch {
                                    case e: Exception => {
                                        log.error(
                                            "watcher: couldn't add VPN", e)
                                    }
                                }
                            }
                        }
                    })
                }
            } catch {
                case e: Exception => { log.error("handleVpn", e) }
            }
        }

        // Delete served VPN.
        for {
            (vpnId, vpn) <- vpnIdToConfig
            if !vpns.contains(vpnId)
        } {
            delVpn(vpnId)
        }
    }

    private def watchVpn(): Iterator[UUID] = {
        log.debug("watchVpn")

        val vpnNodes = vpnMgr.listAll(new Runnable {
            override def run() {
                if (running) { watchVpn }
            }
        }).toIterator

        val vpnMap = (for (vpnId <- vpnNodes) yield (vpnId, vpnMgr.get(vpnId))).toMap
        handleVpn(vpnMap)

        return vpnNodes
    }

    def start() {
        log.debug("start")

        running = true
        watchVpn
    }

    def stop() {
        log.debug("stop")

        running = false
        for ((vpnId, vpn) <- vpnIdToConfig) {
            delVpn(vpnId)
        }
    }
}
