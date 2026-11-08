#!/usr/bin/env python3
"""Rename the wonder provider kernel module: oneplus13_wonder_provider -> opairshare_wonder_provider.

Why binary surgery: no kernel build env on this machine, and the module
name cannot be changed at load time. The kernel reads the module name from
two places, both patched here:
  1. `.modinfo` tag `name=`   - early checks (duplicate module, modinfo tools).
     The new value is 1 byte longer; `.modinfo` content is rewritten freely
     (tags are parsed sequentially, never by offset) and the description is
     shortened ("OnePlus 13 " prefix dropped) to keep the section size.
  2. `.gnu.linkonce.this_module` - struct module .name char[56] field, the
     authoritative name for sysfs/lsmod/rmmod. 26+1 bytes fit the 56-byte
     field in place.

Untouched on purpose:
  - `.note.gnu.build-id` (kernel-identity.sh NOTE values stay valid),
  - `.rodata` pr_info prefix (cosmetic log, cannot grow in place),
  - `.debug_*`/`.strtab` (not loaded at runtime),
  - wonder.ko (binds by symbol, contains no provider-name string).
"""
import struct, sys

OLD = b'oneplus13_wonder_provider'
NEW = b'opairshare_wonder_provider'
assert len(NEW) == len(OLD) + 1

src = sys.argv[1]
dst = sys.argv[2]
data = bytearray(open(src, 'rb').read())
print("in: %s (%d bytes)" % (src, len(data)))

e_shoff, = struct.unpack_from('<Q', data, 0x28)
e_shentsize, e_shnum, e_shstrndx = struct.unpack_from('<HHH', data, 0x3A)
sections = []
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    fields = struct.unpack_from('<IIQQQQIIQQ', data, off)
    sections.append(dict(hdr_off=off, name_off=fields[0], offset=fields[4], size=fields[5]))
shstr = sections[e_shstrndx]
for s in sections:
    end = data.index(b'\0', shstr['offset'] + s['name_off'])
    s['name'] = data[shstr['offset'] + s['name_off']:end].decode()

# --- 1. this_module: struct module .name field, in place ---
tm = next(s for s in sections if s['name'] == '.gnu.linkonce.this_module')
needle = OLD + b'\x00'
i = data.find(needle, tm['offset'], tm['offset'] + tm['size'])
assert i >= 0, 'this_module name field not found'
assert data.find(needle, i + 1, tm['offset'] + tm['size']) < 0, 'multiple name hits'
# Equal-length overwrite: NEW (26) fills OLD's 25 chars + the \0 terminator
# slot; the byte after it is struct-zero padding and becomes the terminator.
assert data[i + len(OLD) + 1] == 0, 'no zero padding after name field'
data[i:i + len(NEW)] = NEW
assert data[i + len(NEW)] == 0
print("this_module name patched @%d" % i)

# --- 2. modinfo: rewrite content, shorten description to pay for the name ---
mi = next(s for s in sections if s['name'] == '.modinfo')
raw = bytes(data[mi['offset']:mi['offset'] + mi['size']])
tags = [t for t in raw.split(b'\0') if t]
out = bytearray()
for t in tags:
    if t.startswith(b'name='):
        out += b'name=' + NEW + b'\x00'
    elif t.startswith(b'description='):
        desc = t.decode().replace('OnePlus 13 ', '')
        out += desc.encode() + b'\x00'
    else:
        out += t + b'\x00'
assert len(out) <= mi['size'], 'modinfo content grew (%d > %d)' % (len(out), mi['size'])
data[mi['offset']:mi['offset'] + mi['size']] = bytes(out) + b'\x00' * (mi['size'] - len(out))
print("modinfo rewritten: %d -> %d bytes (section size %d kept)" % (len(raw), len(out), mi['size']))

# --- sanity: no live-section occurrence of the old name remains ---
for s in sections:
    if s['name'] in ('.modinfo', '.gnu.linkonce.this_module'):
        assert data[s['offset']:s['offset'] + s['size']].find(OLD) < 0, s['name']
# build-id note preserved
note = next(s for s in sections if s['name'] == '.note.gnu.build-id')
print("build-id note intact @%d size=%d" % (note['offset'], note['size']))

open(dst, 'wb').write(bytes(data))
print("out: %s (%d bytes)" % (dst, len(data)))
