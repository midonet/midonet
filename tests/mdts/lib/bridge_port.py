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
        return self._data.get('id')

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
