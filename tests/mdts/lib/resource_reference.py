""" A class for representing a reference to a resource such as device port with
device name and port ID.
"""

class ResourceReference:
    """
    This class represents a reference to a resource, such as device port, from
    an attribute of another resource.
    """

    def __init__(self, resource, setter, reference_spec):
        """Constructs a reference from one resource to another.

        Args:
            resource: A resource whose attribute refers to another resource.
            setter: A setter method name for the attribute.
            reference_spec: A specification of a referenced resource.

        Returns: A new resource reference.
        """
        self._referrer = resource
        self._referrer_setter = setter
        self._reference_spec = reference_spec

    def __eq__(self, another):
        """Defines equality among ResourceReference objects."""
        return (self._referrer == another._referrer and
            self._referrer_setter == another._referrer_setter and
            self._reference_spec == another._reference_spec)

    def get_referrer(self):
        """Returns a referrer resource."""
        return self._referrer

    def get_referrer_setter(self):
        """Returns a referrer resource setter."""
        return self._referrer_setter

    def get_reference_spec(self):
        """Returns a resource reference spec."""
        return self._reference_spec

    def resolve_reference(self, value):
        """Resolve a resource reference with a passed value."""
        getattr(self._referrer, self._referrer_setter)(value)