import functools
import uuid

from ..protobuf import utils
from . import get as base_get
from . import get_all as base_get_all
from . import TYPE
from . import msg_type_map

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
