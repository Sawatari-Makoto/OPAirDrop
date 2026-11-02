#!/usr/bin/env python3
"""Derive the kernel ABI family of a kernel module from its vermagic.

A kernel module only loads on a kernel whose release matches the vermagic it
was compiled with. The release string of a device carries a build id of its
own (`6.6.118-android15-8-g<hash>-abogki<n>-4k`), so the whole string can
never be compared against a fixed value. The release family is the part both
strings share (`6.6.118-android15-8`), plus the page-size flavour the module
was compiled for (`4k`).

The stock build ids of a device are a different matter: they differ per
device and per firmware, so they are read on the device itself at install
time instead of being baked into a package.
"""
import re
from pathlib import Path

VERMAGIC = re.compile(rb'vermagic=([0-9A-Za-z._+-]+)')
PAGE_SIZE = re.compile(r'^\d+k$')
BASE_PART = re.compile(r'^(\d+(?:\.\d+)*|android\d+)$')


def vermagic(path):
    """Return the vermagic string of a kernel module."""
    blob = Path(path).read_bytes()
    match = VERMAGIC.search(blob)
    if not match:
        raise ValueError(f'{path}: no vermagic found')
    return match.group(1).decode('ascii')


def release_family(release):
    """Return the kernel release family shared by a vermagic and uname -r."""
    parts = release.split('-')
    if parts and PAGE_SIZE.match(parts[-1]):
        parts = parts[:-1]
    family = []
    for part in parts:
        if not BASE_PART.match(part):
            break
        family.append(part)
    if not family:
        raise ValueError(f'unrecognised kernel release: {release}')
    return '-'.join(family)


def page_size(release):
    """Return the page-size flavour of a release, or an empty string."""
    parts = release.split('-')
    if parts and PAGE_SIZE.match(parts[-1]):
        return parts[-1]
    return ''


def abi(path):
    """Return the (family, page size) pair of a kernel module."""
    release = vermagic(path)
    return release_family(release), page_size(release)


if __name__ == '__main__':
    import sys
    for argument in sys.argv[1:]:
        family, size = abi(argument)
        print(f'{argument}: family={family} page_size={size}')
