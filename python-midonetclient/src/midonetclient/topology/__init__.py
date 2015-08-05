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
import logging
import socket
import uuid

from midonetclient.protobuf import DecodeError as PBDecodeError
from midonetclient.protobuf import utils
from midonetclient.topology._protobuf import topology_api_pb2
from midonetclient.topology._protobuf import topology_pb2


LOG = logging.getLogger(__name__)


class TopologyError(IOError):
    pass


class HandshakeError(TopologyError):
    pass


class SnapshotError(TopologyError):
    pass


class UpdateError(TopologyError):
    pass


def get(sock, kind, obj_uuid, req_id=None):
    """Returns a Protobuf Response object for a Request.Get command"""
    if req_id is None:
        req_id = uuid.uuid4()
    get = _get_msg(req_id, kind, obj_uuid)
    sock.send(utils.encode_delimited(get.SerializeToString()))
    response = topology_api_pb2.Response()
    try:
        response.ParseFromString(utils.get_answer(sock))
    except socket.timeout:
        issue = 'Timed out receiving Get protobuf response'
        LOG.exception(issue)
        raise TopologyError(issue)
    except PBDecodeError:
        issue = 'Failed to parse Get protobuf response'
        LOG.exception(issue)
        raise TopologyError(issue)
    return response


def get_all(sock, kind, req_id=None):
    """Generates Protobuf Response object for a Request.Get snapshot command"""
    if req_id is None:
        req_id = uuid.uuid4()
    response = get(sock, kind, obj_uuid=None, req_id=req_id)
    if response.type != topology_api_pb2.ResponseType.Value('SNAPSHOT'):
        raise SnapshotError(
            'Failed to get snapshot from the topology API. Got response: %s' %
            utils.proto_to_dict(response))
    for obj_id in response.snapshot.obj_ids:
        py_uuid = utils.uuid_to_UUID(obj_id)
        obj_response = get(sock, kind, obj_uuid=py_uuid, req_id=req_id)
        if obj_response.type != topology_api_pb2.ResponseType.Value('UPDATE'):
            raise UpdateError(
                'Failed to get object %s from the topology API. Got response: '
                '%s' % (py_uuid, utils.proto_to_dict(obj_response)))
        yield obj_response


def handshake(sock, cnxn_id, req_id):
    """Performs the initial handshake operation with the topology server"""
    sock.send(utils.encode_delimited(
        _handshake_msg(cnxn_id, req_id).SerializeToString()))
    response = topology_api_pb2.Response()
    try:
        response.ParseFromString(utils.get_answer(sock))
    except socket.timeout:
        issue = 'Timed out receiving Handshake protobuf response'
        LOG.exception(issue)
        raise TopologyError(issue)
    except PBDecodeError:
        issue = 'Failed to parse Handshake protobuf response'
        LOG.exception(issue)
        raise TopologyError(issue)
    if response.type != topology_api_pb2.ResponseType.Value('ACK'):
        raise HandshakeError(
            'Failed to handshake with the topology API. Got response %s' %
            utils.proto_to_dict(response))


def bye(sock, req_id):
    """Performs the final request release operation with the topology server"""
    sock.send(utils.encode_delimited(_bye_msg(req_id).SerializeToString()))
    sock.settimeout(1)
    try:
        sock.recv(1)
    except socket.timeout:
        issue = 'Timed out checking for the request release'
        LOG.exception(issue)
        raise TopologyError(issue)
    finally:
        sock.close()


def _handshake_msg(cnxn_id=None, req_id=None):
    """Msg for starting a session with the topology server"""
    if cnxn_id is None:
        cnxn_id = uuid.uuid4()  # Random uuid
    if req_id is None:
        req_id = uuid.uuid4()  # Random uuid
    request = topology_api_pb2.Request()
    request.handshake.cnxn_id.msb, request.handshake.cnxn_id.lsb = (
            utils.split_uuid(cnxn_id))
    request.handshake.req_id.msb, request.handshake.req_id.lsb = (
            utils.split_uuid(req_id))
    return request


def _bye_msg(req_id):
    """Msg for notifying the server of the end of session

    Returns a message that can be sent to server to notify of the end of the
    current session, whereupon the server is free to close the socket
    """

    request = topology_api_pb2.Request()
    request.bye.req_id.msb, request.bye.req_id.lsb = (
            utils.split_uuid(req_id))
    return request


def _get_msg(req_id, kind, obj_uuid):
    """Msg for fetching resources from the topology api with the specified type

    Returns:
        - A topology_pb2.update object if obj_uuid is specified, or
        - a topology_api_pb2.Response.snapshot of all the uuids of the
          specified kind.
    """
    request = topology_api_pb2.Request()
    request.get.req_id.msb, request.get.req_id.lsb = (
            utils.split_uuid(req_id))
    request.get.type = kind
    if obj_uuid is not None:
        request.get.id.msb, request.get.id.lsb = utils.split_uuid(obj_uuid)
    return request


_get_all_msg = functools.partial(_get_msg, obj_uuid=None)


# Common convenient conversions that may be used by topology response
# conversion to common python types.
msg_type_map = {
    'org.midonet.cluster.models.IPAddress.version':
    lambda x: {'V4': 4, 'V6': 6}.get(x),
    'org.midonet.cluster.models.UUID':
    lambda x: str(utils.uuid_to_UUID(x))
}

TYPE = dict(topology_pb2.Type.items())
