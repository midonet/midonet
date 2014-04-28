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
