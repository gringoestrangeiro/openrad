import unittest

import radproto as p


def announce(rid, include_server, state):
    payload = (p.u64(0x020001e1, rid) + p.tlv(0x0d000309, b'\x0a' * 16)
               + p.u32(0x01000305, 0x1a000000 + rid) + p.text16(0x03000304, 'new-device')
               + p.u32(0x0100030a, 1))
    if include_server:
        payload += p.text16(0x030001c9, '192.0.2.10')
    if state is not None:
        payload += p.u32(0x010003a0, state)
    return p.u32(p.SERVER_OPERATION, 41) + p.tlv(0x131f, p.tlv(0x131e, payload))


class MembershipTests(unittest.TestCase):
    def test_live_member_announcement_without_presence_can_connect_but_explicit_offline_cannot(self):
        members = p.Membership(1)
        members.snapshot(p.tlv(0x1316, p.tlv(0x1315, p.text16(0x03000306, 'Private')
            + p.tlv(0x0d000309, b'\x0a' * 16) + p.u32(0x0100030a, 2))))
        members.changes(announce(7, True, None))
        self.assertEqual(members.peers[7]['state'], 1)
        self.assertIn(7, members.eligible())
        members.changes(announce(8, True, 0))
        members.changes(announce(9, False, None))
        self.assertEqual(members.peers[8]['state'], 0)
        self.assertEqual(members.peers[9]['state'], 0)
        self.assertEqual(set(members.eligible()), {7})
        offline = p.tlv(0x1366, p.u64(0x020001e1, 7) + p.u32(0x010003a0, 0))
        members.changes(p.u32(p.SERVER_OPERATION, 41) + p.tlv(0x131f, offline))
        self.assertEqual(members.peers[7]['state'], 0)
        self.assertFalse(members.eligible())


if __name__ == '__main__':
    unittest.main()
