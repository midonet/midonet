# Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.

class AdminStateUpMixin:
    def disable(self):
        self._setAdminState(False)

    def enable(self):
        self._setAdminState(True)

    def _setAdminState(self, state):
        getattr(self._mn_resource, "admin_state_up")(state)
        self._mn_resource.update()