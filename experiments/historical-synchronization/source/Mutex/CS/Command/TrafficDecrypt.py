"""Historical RC4 research helper with caller-supplied, non-embedded material."""

import base64
import gzip
import hashlib
from Crypto.Cipher import ARC4


def decrypt_transport_value(security_b64, nonce_b64, encrypted_b64, compressed=False):
    """Decrypt one explicitly supplied fixture; this module has no import-time data or I/O."""
    signed = hashlib.sha256(
        base64.b64decode(security_b64) + base64.b64decode(nonce_b64)
    ).digest()
    cipher = ARC4.new(signed)
    cipher.encrypt(bytes(1024))
    decoded = cipher.encrypt(base64.b64decode(encrypted_b64))
    return gzip.decompress(decoded) if compressed else decoded
