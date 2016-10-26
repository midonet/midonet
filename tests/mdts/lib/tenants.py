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

import uuid


class Tenant:

    def __init__(self, id, name):
        self.id = id
        self.name = name

TENANTS_LIST = []
TENANTS_TABLE = {}


def get_or_create_tenant(name):
    if TENANTS_TABLE.has_key(name):
        return TENANTS_TABLE[name]
    # generate a unique UUID to a tenant name based on its hash
    namespace = uuid.UUID('ec9c48eb-a3b3-489c-9bb2-beb37fbb8c5e')
    tenant = Tenant(str(uuid.uuid5(namespace, name)), name)
    TENANTS_LIST.append(tenant)
    TENANTS_TABLE[tenant.name] = tenant
    return tenant


def get_tenant(name):
    return TENANTS_TABLE[name]


def list_tenants():
    return TENANTS_LIST

for name in ('midonet_provider', 'tenant0', 'tenant1',
             'MMM-TEST-000-001', 'MMM-TEST-000-002'):
    tenant = get_or_create_tenant(name)
    TENANTS_LIST.append(tenant)
    TENANTS_TABLE[name] = tenant
