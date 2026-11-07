#!/usr/bin/env python3
"""Write private Java Properties without changing signing secrets through escaping."""
import os
from pathlib import Path
import sys


def escape_value(value):
    escaped = []
    for character in value:
        if character in '\\ \t\r\n\f':
            escaped.append({'\\': r'\\', ' ': r'\ ', '\t': r'\t', '\r': r'\r', '\n': r'\n', '\f': r'\f'}[character])
        elif not 0x21 <= ord(character) <= 0x7e:
            # Properties.load(InputStream) decodes ISO-8859-1. Escaping UTF-16
            # code units also preserves supplementary Unicode characters.
            units = character.encode('utf-16-be', errors='surrogatepass')
            escaped.extend('\\u' + units[i:i + 2].hex() for i in range(0, len(units), 2))
        else:
            escaped.append(character)
    return ''.join(escaped)


def write_properties(destination, keystore, environ=os.environ):
    values = {'storeFile': str(keystore),
              'storePassword': environ['RELEASE_STORE_PASSWORD'],
              'keyAlias': environ['RELEASE_KEY_ALIAS'],
              'keyPassword': environ['RELEASE_KEY_PASSWORD']}
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(destination, flags, 0o600)
    with os.fdopen(fd, 'w', encoding='ascii', newline='\n') as stream:
        for name, value in values.items():
            stream.write(name + '=' + escape_value(value) + '\n')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: write-android-signing-properties.py OUTPUT KEYSTORE')
    write_properties(Path(sys.argv[1]), Path(sys.argv[2]))
