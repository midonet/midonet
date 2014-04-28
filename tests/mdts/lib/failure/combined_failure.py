import string
import sys

from mdts.lib.failure.failure_base import FailureBase

LOG = logging.getLogger(__name__)

class CombinedFailure(FailureBase):
    """Emulate multiple failures that happen simultaneously

    @failures   failure list

    """
    def __init__(self, failures):
        super(CombinedFailure, self).__init__(
            string.join(map(lambda x: x.__name__, failures), " and "))
        self._failures = failures

    def inject(self):
        for failure in self._failures:
            failure.inject()

    def eject(self):
        for failure in reversed(self._failures):
            try:
                failure.eject()
            except:
                LOG.exception(sys.exc_info()[1])
