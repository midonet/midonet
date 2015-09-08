# Copyright (c) 2015 Midokura SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from midonetclient import condition
from midonetclient import resource_base
from midonetclient import vendor_media_type
from midonetclient import vtep_binding

class TraceRequest(resource_base.ResourceBase, condition.ConditionBase):
    media_type = vendor_media_type.APPLICATION_TRACE_REQUEST_JSON

    def __init__(self, uri, dto, auth):
        super(TraceRequest, self).__init__(uri, dto, auth)
        resource_base.ResourceBase.__init__(self, uri, dto, auth)
        condition.ConditionBase.__init__(self, self.condition_dto)

    def condition_dto(self):
        if not self.dto.has_key('condition'):
            self.dto['condition'] = dict()
        return self.dto['condition']

    def get_id(self):
        return self.dto['id']

    # name
    def get_name(self):
        return self.dto['name']

    def set_name(self, name):
        self.dto['name'] = name
        return self

    # port
    def get_port(self):
        if (self.dto['deviceType'] == "PORT"):
            return self.dto['deviceId']
        else:
            raise KeyError("device isn't a PORT")

    def set_port(self, port):
        self.dto['deviceType'] = "PORT"
        self.dto['deviceId'] = port
        return self

    # router
    def get_router(self):
        if (self.dto['deviceType'] == "ROUTER"):
            return self.dto['deviceId']
        else:
            raise KeyError("device isn't a ROUTER")

    def set_router(self, router):
        self.dto['deviceType'] = "ROUTER"
        self.dto['deviceId'] = router
        return self

    # bridge
    def get_bridge(self):
        if (self.dto['deviceType'] == "BRIDGE"):
            return self.dto['deviceId']
        else:
            raise KeyError("device isn't a BRIDGE")

    def set_bridge(self, bridge):
        self.dto['deviceType'] = "BRIDGE"
        self.dto['deviceId'] = bridge
        return self

    # limit
    def get_limit(self):
        return self.dto['limit']

    def set_limit(self, limit):
        self.dto['limit'] = limit
        return self

    # enabled
    def get_enabled(self):
        return self.dto['enabled']

    def set_enabled(self, enabled):
        self.dto['enabled'] = enabled
        return self


