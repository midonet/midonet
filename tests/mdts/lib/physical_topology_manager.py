# Copyright 2014 Midokura SARL
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Resource manager for physical topology data.
"""

import logging

from mdts.lib.topology_manager import TopologyManager
from mdts.services import service
from mdts.tests.utils.conf import is_vxlan_enabled


LOG = logging.getLogger(__name__)


class PhysicalTopologyManager(TopologyManager):

    def __init__(self, filename=None, data=None):

        super(PhysicalTopologyManager, self).__init__(filename, data)
        self._hosts = self._data['physical_topology'].get('hosts')
        self._compute_hosts = service.get_all_containers('midolman')
        self._external_hosts = service.get_all_containers('externalhost')
        self._bridges = self._data['physical_topology'].get('bridges') or []
        self._interfaces = {}  # (host_id, interface_id) to interface map

    def build(self):
        """
        Build physical topology from the data.

        Args:
            filename: filename that defines physical topology
            data: python dictionary object to represent the physical topology

        """

        LOG.debug('-' * 80)
        LOG.debug("build")
        LOG.debug('-' * 80)
        #for b in self._bridges:
        #    bridge = b['bridge']
        #    # TODO(tomohiko) Need to something when not bridge['provided']?
        #    if bridge['provided']:
        #        LOG.info('Skipped building bridge=%r', bridge)

        midonet_api = self._midonet_api_host.get_midonet_api()

        # Check if TZ exists and pick it if that's the case
        tzones = midonet_api.get_tunnel_zones()
        tz = None
        for tzone in tzones:
            if tzone.get_name() == 'mdts-test':
                tz = tzone

        # IF TZ does not exists, create it
        if tz is None:
            if is_vxlan_enabled():
                tz = midonet_api.add_vxlan_tunnel_zone()
            else:
                tz = midonet_api.add_gre_tunnel_zone()
            tz.name('mdts-test')
            tz.create()

        for host in self._compute_hosts:
            tz_hosts = tz.get_hosts()
            tz_host = filter(
                lambda x: x.get_host_id() == host.get_midonet_host_id(),
                tz_hosts)
            if not tz_host:
                tz_host = tz.add_tunnel_zone_host()
                tz_host.ip_address(host.get_ip_address())
                tz_host.host_id(host.get_midonet_host_id())
                tz_host.create()

        # Create mapping between host['id'], interface['id'] with interface
        # description.
        for h in self._hosts:
            host = h['host']
            if 'mn_host_id' not in host:
                hostname = host.get('hostname')
                host_id = host.get('id')
                externalhost = next(external for external in self._external_hosts
                                    if external.get_hostname() == hostname)
                interfaces = host['interfaces']
                for interface in interfaces:
                    interface = interface['interface']
                    if interface['type'] == 'provided':
                        interface_id = interface.get('id')
                        iface = externalhost.create_provided(**interface)
                        self._interfaces[(host_id, interface_id)] = iface

        LOG.debug('-' * 80)
        LOG.debug("end build")
        LOG.debug('-' * 80)

    def destroy(self):

        LOG.debug('-' * 80)
        LOG.debug("destroy")
        LOG.debug('-' * 80)

        midonet_api_host = service.get_container_by_hostname('cluster1')
        midonet_api = midonet_api_host.get_midonet_api()
        tzs = midonet_api.get_tunnel_zones()
        mdts_tzs = filter(lambda t: t.get_name() == 'mdts-test', tzs)
        map(lambda tz: tz.delete(), mdts_tzs)

        for interface in self._interfaces.values():
            interface.destroy()
        self._interfaces = {}

        LOG.debug('-' * 80)
        LOG.debug("end destroy")
        LOG.debug('-' * 80)

    def get_interface(self, host_id, interface_id):
        return self._interfaces.get((host_id, interface_id))
