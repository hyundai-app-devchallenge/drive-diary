"""Text policy reference implementation; no persistence or device dependencies."""
from .gate import AppSignals, PolicyGate, gate

__all__ = ["AppSignals", "PolicyGate", "gate"]
