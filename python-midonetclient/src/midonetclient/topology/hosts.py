# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright (c) 2015 Midokura Europe SARL, All Rights Reserved.
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

import functools
import uuid

from midonetclient.protobuf import utils
from midonetclient.topology import get as base_get
from midonetclient.topology import get_all as base_get_all
from midonetclient.topology import msg_type_map
from midonetclient.topology import TYPE

get = functools.partial(base_get, kind=TYPE['HOST'])
get_all = functools.partial(base_get_all, kind=TYPE['HOST'])


def get_dict(sock, obj_uuid):
    """Returns the Host dict that corresponds to the specified uuid string"""
    return utils.proto_to_dict(
        get(sock, obj_uuid=uuid.UUID(hex=obj_uuid)).update.host,
        message_type_map=msg_type_map)


def get_all_dict(sock):
    for response in get_all(sock):
        yield utils.proto_to_dict(response.update.host,
                                  message_type_map=msg_type_map)
