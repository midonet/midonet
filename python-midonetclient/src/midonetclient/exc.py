# vim: tabstop=4 shiftwidth=4 softtabstop=4

# Copyright 2013 Midokura PTE LTD.
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

from webob import exc


code_exception_map = dict((str(e.code), e) for e in
                          exc.HTTPClientError.__subclasses__() +
                          exc.HTTPServerError.__subclasses__())


def get_exception(status_code):
    return code_exception_map[status_code]


class MidoApiConnectionError(Exception):
    pass


class MidoApiConnectionRefused(MidoApiConnectionError):
    def __init__(self):
        MidoApiConnectionError.__init__(
            self, "Could not connect to the midonet-api.")
