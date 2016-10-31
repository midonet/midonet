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


class FailureBase(object):
    """Base class for failure

    @name       failure name
    @resilient  resilient failure [boolean]

    NOTE: you have to mark a failure as ``resilient'' to use it with
          the failure decorator; a failure used with the decorator has
          to be resilient, so it automatically recovers after ejected.
          Otherwise tests are going to fail after the failure is
          injected and even ejected via the decorator.
    """
    def __init__(self, name, resilient=True):
        self.__name__ = name
        self._resilient = resilient

    def is_resilient(self):
        return self._resilient

    def inject(self):
        pass

    def eject(self):
        pass
