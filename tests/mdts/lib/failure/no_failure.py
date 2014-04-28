from mdts.lib.failure.failure_base import FailureBase

class NoFailure(FailureBase):
    """Pesudo faliure to show no failure has occured."""

    def __init__(self):
        super(NoFailure, self).__init__("no_failure")

    def inject(self):
        pass

    def eject(self):
        pass
