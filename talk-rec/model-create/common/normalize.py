"""Text normalization shared by training and the device implementation."""

import unicodedata


def normalize(text: str) -> str:
    """Apply NFKC, lowercase, and collapse non-letter/number runs to spaces.

    Unicode categories L* and N* match Java's \\p{L} and \\p{N}; combining
    marks and underscores are separators. Length limits belong to callers.
    """
    text = unicodedata.normalize("NFKC", text).lower()
    separated = "".join(
        char if unicodedata.category(char)[0] in "LN" else " " for char in text
    )
    return " ".join(separated.split())
