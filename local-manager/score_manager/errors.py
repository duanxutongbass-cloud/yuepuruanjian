class Invalid(ValueError):
    """Rejected input or a conflicting operation; no partial commit."""


class Cancelled(Exception):
    """Caller cancelled before the transaction commit point."""


def require(condition, message):
    if not condition:
        raise Invalid(message)


def checkpoint(cancel):
    if cancel is not None and cancel():
        raise Cancelled("Operation cancelled; transaction was not committed")
