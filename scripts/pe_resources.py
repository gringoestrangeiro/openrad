"""Read PE resources on Linux without loading or executing the binary."""
import struct
from pathlib import Path


def resources(path):
    data = Path(path).read_bytes()
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    if data[pe:pe + 4] != b'PE\0\0':
        raise ValueError('Not a PE file')
    count = struct.unpack_from('<H', data, pe + 6)[0]
    optional_size = struct.unpack_from('<H', data, pe + 20)[0]
    optional = pe + 24
    magic = struct.unpack_from('<H', data, optional)[0]
    directories = optional + (112 if magic == 0x20b else 96)
    resource_rva, resource_size = struct.unpack_from('<II', data, directories + 16)
    if not resource_size:
        return {}
    sections = []
    for index in range(count):
        offset = optional + optional_size + index * 40
        virtual_size, rva, size, raw = struct.unpack_from('<IIII', data, offset + 8)
        sections.append((rva, max(virtual_size, size), raw))

    def locate(rva):
        for start, size, raw in sections:
            if start <= rva < start + size:
                return raw + rva - start
        raise ValueError('Resource RVA is outside PE sections')

    base = locate(resource_rva)
    result = {}

    def visit(relative, prefix, depth):
        if depth > 4:
            raise ValueError('Resource directory is too deep')
        named, ids = struct.unpack_from('<HH', data, base + relative + 12)
        for index in range(named + ids):
            name, value = struct.unpack_from('<II', data, base + relative + 16 + index * 8)
            if name & 0x80000000:
                start = base + (name & 0x7fffffff)
                length = struct.unpack_from('<H', data, start)[0]
                name = data[start + 2:start + 2 + length * 2].decode('utf-16-le')
            path = prefix + (name,)
            if value & 0x80000000:
                visit(value & 0x7fffffff, path, depth + 1)
            else:
                rva, size = struct.unpack_from('<II', data, base + value)
                start = locate(rva)
                payload = data[start:start + size]
                if len(payload) != size:
                    raise ValueError('Truncated resource')
                result[path] = payload

    visit(0, (), 0)
    return result
