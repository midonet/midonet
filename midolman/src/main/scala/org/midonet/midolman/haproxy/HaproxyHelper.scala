package org.midonet.midolman.haproxy

import scala.sys.process._

class HaproxyHelper(haproxyScript: String) {

    def makens(name: String, iface: String, mac: String, ip: String,
               routerIp: String) = {
        s"$haproxyScript makens $name $iface $mac $ip $routerIp".!!
    }

    def cleanns(name: String, iface: String) = {
        try {
            s"$haproxyScript cleanns $name $iface".!!
        } catch {
            case e: RuntimeException =>
            // expected if the namespace is already cleaned
        }
    }

    def restartHaproxy(name: String, confLocation: String, pidLocation: String) = {
        s"$haproxyScript restart_ha $name $confLocation $pidLocation".!!
    }
}
