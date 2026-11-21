#!/usr/bin/env python3
"""Verify packaged ABIs, Rime resources and every ELF LOAD alignment."""
import struct
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    libs = [n for n in apk.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert {n.split('/')[1] for n in libs} == {'arm64-v8a', 'x86_64'}, libs
    for abi in ('arm64-v8a', 'x86_64'):
        assert f'lib/{abi}/libxyu_rime.so' in libs
    for asset in ('default.yaml', 'xyu_pinyin.schema.yaml', 'pinyin_simp.dict.yaml'):
        assert f'assets/rime/{asset}' in apk.namelist(), asset
    for name in libs:
        data = apk.read(name)
        assert b'RimeTestProbe' not in data, (name, 'debug native probe must not ship')
        assert data[:6] == b'\x7fELF\x02\x01', name
        phoff = struct.unpack_from('<Q', data, 32)[0]
        entsize, count = struct.unpack_from('<HH', data, 54)
        loads = 0
        for i in range(count):
            kind, flags, offset, address, _, _, _, alignment = struct.unpack_from('<IIQQQQQQ', data, phoff + i * entsize)
            if kind == 1:
                loads += 1
                assert alignment >= 16384 and alignment % 16384 == 0, (name, alignment)
                assert (address - offset) % 16384 == 0, (name, offset, address)
        assert loads, name
        print(f'{name}: {loads} LOAD segments aligned to 16 KB')
    for name in apk.namelist():
        if name.endswith('.dex'):
            assert b'RimeTestProbe' not in apk.read(name), 'debug JVM probe must not ship'
    print('Both ABIs, bundled Rime resources, and absence of debug probes verified')
