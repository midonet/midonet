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

from mdts.lib.bridge_port import BridgePort
from mdts.lib.dhcp_subnet import DhcpSubnet
from mdts.lib.resource_base import ResourceBase

# A list of
#   - filter id attribute name in Bridge DTO, and
#   - data field for the corresponding filter
_FILTER_SETTERS = [
    ('inbound_filter_id', '_inbound_filter'),
    ('outbound_filter_id', '_outbound_filter')
]


class Bridge(ResourceBase):

    def __init__(self, api, context, data):
        super(Bridge, self).__init__(api, context, data)
        self._ports = {}
        self._dhcp_subnets = {}
        self._inbound_filter = None
        self._outbound_filter = None

    def build(self):
        """ Builds a bridge MidoNet resource.

        On building, if filter names are specified, it looks up Chain data via
        VirtualTopologyManager and assign them to this bridge.
        """
        tenant_id = self._get_tenant_id()
        self._mn_resource = self._api.add_bridge()
        self._mn_resource.tenant_id(tenant_id)
        self._mn_resource.name(self._get_name())

        # Take filter names specified in the yaml file, look up corresponding
        # chain data via Virtual Topology Manager, and set their chain IDs to
        # Bridge DTO. Raise an exception if no corresponding chain is found.
        # TODO(tomohiko) Also updates _inbound_filter and _outbound_filter
        for filter_field in ['inbound_filter_id', 'outbound_filter_id']:
            if filter_field in self._data:
                self._context.look_up_resource(
                        self._mn_resource, filter_field, self._data[filter_field])

        if 'qos_policy' in self._data:
            qp_name = self._data['qos_policy']
            qp_id = self._context._qos_policies[qp_name].get_id()
            self._mn_resource.qos_policy_id(qp_id)

        self._mn_resource.create()

        for port in self._data.get('ports') or []:
            self.add_port(port['port'])

        for dhcp in self._data.get('dhcps') or []:
            self.add_dhcp_subnet(dhcp['dhcp'])

    def update(self):
        """ Dynamically updates filter data assigned to the bridge. """
        for (bridge_field, wrapper_field) in _FILTER_SETTERS:
            if getattr(self, wrapper_field):
                getattr(self._mn_resource, bridge_field)(
                        getattr(self, wrapper_field)._mn_resource.get_id())
            else:
                getattr(self._mn_resource, bridge_field)(None)
        self._mn_resource.update()

    def destroy(self):
        self._mn_resource.delete()

    def get_id(self):
        return self._mn_resource.get_id()

    def get_port(self, port_id):
        return self._ports.get(port_id)

    def add_port(self, port):
        port_obj = BridgePort(self._api, self._context, self, port)
        port_obj.build()
        self._ports[port['id']] = port_obj

    def add_dhcp_subnet(self, dhcp):
        subnet_obj = DhcpSubnet(self._api, self._context, self, dhcp)
        subnet_obj.build()
        self._dhcp_subnets[dhcp['id']] = subnet_obj

    def set_inbound_filter(self, rule_chain):
        self._inbound_filter = rule_chain
        self.update()

    def get_inbound_filter(self):
        return self._inbound_filter

    def set_outbound_filter(self, rule_chain):
        self._outbound_filter = rule_chain
        self.update()

    def get_outbound_filter(self):
        return self._outbound_filter

    def set_qos_policy(self, qos_policy):
        self._mn_resource.qos_policy_id(qos_policy.get_id())
        self.update()

    def clear_qos_policy(self):
        self._mn_resource.qos_policy_id(None)
        self.update()
