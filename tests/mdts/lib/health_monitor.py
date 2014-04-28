# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
from mdts.lib.resource_base import ResourceBase

FIELDS = ['admin_state_up', 'delay', 'max_retries', 'timeout']

class HealthMonitor(ResourceBase):
    def __init__(self, api, context, data):
        """
        @type api midonetclient.api.MidonetApi
        @type context mdts.lib.virtual_topology_manager.VirtualTopologyManager
        """
        super(HealthMonitor, self).__init__(api, context, data)

        #: :type: list[Pool]
        self._pools = []

    def build(self):
        self._mn_resource = self._api.add_health_monitor()
        for field in FIELDS:
            getattr(self._mn_resource, field)(self._data[field])
        self.create_resource()

    def destroy(self):
        self._mn_resource.delete()
        pass
