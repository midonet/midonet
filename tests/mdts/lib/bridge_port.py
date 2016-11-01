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

from mdts.lib.resource_base import ResourceBase

# A list of
#   - filter id attribute name in Bridge DTO, and
#   - data field for the corresponding filter
_FILTER_SETTERS = [
    ('inbound_filter_id', '_inbound_filter'),
    ('outbound_filter_id', '_outbound_filter')
]


class BridgePort(ResourceBase):

    def __init__(self, api, context, bridge, data):
        super(BridgePort, self).__init__(api, context, data)
        self._bridge = bridge
        self._inbound_filter = None
        self._outbound_filter = None

    def build(self):
        mn_bridge = self._bridge._mn_resource
        if self._data.get('type') == "interior":
            mn_bridge_port = mn_bridge.add_port()
            if self._data.get('vlan_id'):
                mn_bridge_port.vlan_id(self._data['vlan_id'])
        else:
            mn_bridge_port = mn_bridge.add_port()
            # TODO: add bgps for exterior ports
        self._mn_resource = mn_bridge_port

        if 'qos_policy' in self._data:
            qp_name = self._data['qos_policy']
            qp_id = self._context._qos_policies[qp_name].get_id()
            self._mn_resource.qos_policy_id(qp_id)

        if 'links_to' in self._data:
            self._context.register_link(self, self._data['links_to'])

        mn_bridge_port.create()

    def update(self):
        """Dynamically updates in/out-bound filters set to the bridge port.

        This updates the bridge port MN resource with a filter ID if one has
        been programmatically set in the functional test script to the
        'wrapper_field' attribute.
        """
        for (mn_resource_field, wrapper_field) in _FILTER_SETTERS:
            if getattr(self, wrapper_field):
                getattr(self._mn_resource, mn_resource_field)(
                        getattr(self, wrapper_field)._mn_resource.get_id())
            else:
                getattr(self._mn_resource, mn_resource_field)(None)
        self._mn_resource.update()

    def destroy(self):
        if self._mn_resource.get().get_peer_id():
            self._mn_resource.unlink()
        self._mn_resource.delete()

    def link(self, peer_device_port):
        """Links this device port with the given peer device port."""
        self._mn_resource.link(peer_device_port._mn_resource.get_id())

    def get_id(self):
        return self._mn_resource.get_id()

    def get_device_name(self):
        return self._bridge._get_name()

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

