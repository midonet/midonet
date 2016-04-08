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

package org.midonet.midolman.topology

import com.codahale.metrics.{Gauge, MetricRegistry}
import com.codahale.metrics.MetricRegistry.name
import com.google.inject.Inject

class VirtualTopologyMetrics @Inject()(registry: MetricRegistry) {

    @volatile private var promises = () => 0
    @volatile private var senders = () => 0
    @volatile private var subscribers = () => 0
    @volatile private var devices = () => 0

    @volatile private var bgpPortToBgp = () => 0
    @volatile private var bgpBgpToPort = () => 0
    @volatile private var bgpRequests = () => 0
    @volatile private var bgpBgpToRoute = () => 0
    @volatile private var bgpRouteToConfig = () => 0

    @volatile private var chainRuleMaps = () => 0
    @volatile private var chainRuleIds = () => 0
    @volatile private var chainMissingRuleIds = () => 0

    @volatile private var loadBalancerVipMaps = () => 0
    @volatile private var loadBalancerVipIds = () => 0
    @volatile private var loadBalancerMissingVipIds = () => 0

    @volatile private var poolMemberMaps = () => 0
    @volatile private var poolMemberIds = () => 0
    @volatile private var poolMissingMemberIds = () => 0

    @volatile private var portWatchers = () => 0

    @volatile private var portGroupWatchers = () => 0

    @volatile private var routerPortToRoutes = () => 0
    @volatile private var routerRouterToRoutes = () => 0
    @volatile private var routerPortCallbacks = () => 0
    @volatile private var routerPortWatchers = () => 0

    registry.register(name(classOf[VirtualTopologyMetrics], "promises"),
                      gauge { promises() })
    registry.register(name(classOf[VirtualTopologyMetrics], "senders"),
                      gauge { senders() })
    registry.register(name(classOf[VirtualTopologyMetrics], "subscribers"),
                      gauge { subscribers() })
    registry.register(name(classOf[VirtualTopologyMetrics], "devices"),
                      gauge { devices() })

    registry.register(name(classOf[VirtualTopologyMetrics], "bgpPortToBgp"),
                      gauge { bgpPortToBgp() })
    registry.register(name(classOf[VirtualTopologyMetrics], "bgpBgpToPort"),
                      gauge { bgpBgpToPort() })
    registry.register(name(classOf[VirtualTopologyMetrics], "bgpRequests"),
                      gauge { bgpRequests() })
    registry.register(name(classOf[VirtualTopologyMetrics], "bgpBgpToRoute"),
                      gauge { bgpBgpToRoute() })
    registry.register(name(classOf[VirtualTopologyMetrics], "bgpRouteToConfig"),
                      gauge { bgpRouteToConfig() })

    registry.register(name(classOf[VirtualTopologyMetrics], "chainRuleMaps"),
                      gauge { chainRuleMaps() })
    registry.register(name(classOf[VirtualTopologyMetrics], "chainRuleIds"),
                      gauge { chainRuleIds() })
    registry.register(name(classOf[VirtualTopologyMetrics], "chainMissingRuleIds"),
                      gauge { chainMissingRuleIds() })

    registry.register(name(classOf[VirtualTopologyMetrics], "loadBalancerVipMaps"),
                      gauge { loadBalancerVipMaps() })
    registry.register(name(classOf[VirtualTopologyMetrics], "loadBalancerVipIds"),
                      gauge { loadBalancerVipIds() })
    registry.register(name(classOf[VirtualTopologyMetrics], "loadBalancerMissingVipIds"),
                      gauge { loadBalancerMissingVipIds() })

    registry.register(name(classOf[VirtualTopologyMetrics], "poolMemberMaps"),
                      gauge { poolMemberMaps() })
    registry.register(name(classOf[VirtualTopologyMetrics], "poolMemberIds"),
                      gauge { poolMemberIds() })
    registry.register(name(classOf[VirtualTopologyMetrics], "poolMissingMemberIds"),
                      gauge { poolMissingMemberIds() })

    registry.register(name(classOf[VirtualTopologyMetrics], "portWatchers"),
                      gauge { portWatchers() })

    registry.register(name(classOf[VirtualTopologyMetrics], "portGroupWatchers"),
                      gauge { portGroupWatchers() })

    registry.register(name(classOf[VirtualTopologyMetrics], "routerPortToRoutes"),
                      gauge { routerPortToRoutes() })
    registry.register(name(classOf[VirtualTopologyMetrics], "routerRouterToRoutes"),
                      gauge { routerRouterToRoutes() })
    registry.register(name(classOf[VirtualTopologyMetrics], "routerPortCallbacks"),
                      gauge { routerPortCallbacks() })
    registry.register(name(classOf[VirtualTopologyMetrics], "routerPortWatchers"),
                      gauge { routerPortWatchers() })

    def setPromises(f: () => Int): Unit = { promises = f }

    def setSenders(f: () => Int): Unit = { senders = f }

    def setSubscribers(f: () => Int): Unit = { subscribers = f }

    def setDevices(f: () => Int): Unit = { devices = f }

    def setBgpPortToBgp(f: () => Int): Unit = { bgpPortToBgp = f }

    def setBgpBgpToPort(f: () => Int): Unit = { bgpBgpToPort = f }

    def setBgpRequests(f: () => Int): Unit = { bgpRequests = f }

    def setBgpBgpToRoute(f: () => Int): Unit = { bgpBgpToRoute = f }

    def setBgpRouteToConfig(f: () => Int): Unit = { bgpRouteToConfig = f }

    def setChainRuleMaps(f: () => Int): Unit = { chainRuleMaps = f }

    def setChainRuleIds(f: () => Int): Unit = { chainRuleIds = f }

    def setChainMissingRuleIds(f: () => Int): Unit = { chainMissingRuleIds = f }

    def setLoadBalancerVipMaps(f: () => Int): Unit = { loadBalancerVipMaps = f }

    def setLoadBalancerVipIds(f: () => Int): Unit = { loadBalancerVipIds = f }

    def setLoadBalancerMissingVipIds(f: () => Int): Unit = { loadBalancerMissingVipIds = f }

    def setPoolMemberMaps(f: () => Int): Unit = { poolMemberMaps = f }

    def setPoolMemberIds(f: () => Int): Unit = { poolMemberIds = f }

    def setPoolMissingMemberIds(f: () => Int): Unit = { poolMissingMemberIds = f }

    def setPortWatchers(f: () => Int): Unit = { portWatchers = f }

    def setPortGroupWatchers(f: () => Int): Unit = { portGroupWatchers = f }

    def setRouterPortToRoutes(f: () => Int): Unit = { routerPortToRoutes = f }

    def setRouterRouterToRoutes(f: () => Int): Unit = { routerRouterToRoutes = f }

    def setRouterPortCallbacks(f: () => Int): Unit = { routerPortCallbacks = f }

    def setRouterPortWatchers(f: () => Int): Unit = { routerPortWatchers = f }

    private def gauge(f: => Int) = {
        new Gauge[Int] { override def getValue = f }
    }

}
