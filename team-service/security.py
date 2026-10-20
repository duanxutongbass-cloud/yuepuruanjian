"""Versioned password hashes and opaque, revocable session credentials."""
import hashlib
import hmac
import secrets


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def password_hash(password, salt=None):
    if not isinstance(password, str) or not 12 <= len(password) <= 128:
        raise ValueError("password_length")
    salt = salt or secrets.token_hex(16)
    # OWASP scrypt equivalent work-factor option: N=2^15, r=8, p=3 (32 MiB).
    result = hashlib.scrypt(password.encode("utf-8"), salt=bytes.fromhex(salt),
                            n=32768, r=8, p=3, maxmem=64 * 1024 * 1024, dklen=32)
    return "scrypt$32768$8$3$" + salt + "$" + result.hex()


def password_matches(password, encoded):
    try:
        prefix, n, r, p, salt, _ = encoded.split("$")
        if (prefix, n, r, p) != ("scrypt", "32768", "8", "3"):
            return False
        return hmac.compare_digest(password_hash(password, salt), encoded)
    except (TypeError, ValueError):
        return False
