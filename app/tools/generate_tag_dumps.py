"""Regenerates the MIFARE Classic tag dumps in src/test/resources/tagdumps.

The dumps are synthetic: they are built to the block layout published by the Bambu
Research Group, and their sector trailers carry the Key A values that the published
HKDF derivation produces for each dump's tag UID. Keeping the generator in the repo
means the fixtures can be audited and regenerated rather than taken on trust, and the
Python HKDF here is what the Kotlin implementation is pinned against in
BambuTagKeysTest.

Run:  python3 nfc/tools/generate_tag_dumps.py
"""

import hashlib, hmac, struct, os

MASTER = bytes([0x9a,0x75,0x9c,0xf2,0xc4,0xf7,0xca,0xff,0x22,0x2c,0xb9,0x76,0x9b,0x41,0xbc,0x96])
INFO = b"RFID-A\x00"

def hkdf(ikm, salt, info, length):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    okm, t, i = b"", b"", 0
    while len(okm) < length:
        i += 1
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        okm += t
    return okm[:length]

def derive_keys(uid):
    okm = hkdf(uid, MASTER, INFO, 6 * 16)
    return [okm[i*6:(i+1)*6] for i in range(16)]

def blk():
    return bytearray(16)

def put_str(b, off, s, n):
    raw = s.encode("ascii")
    assert len(raw) <= n, s
    b[off:off+len(raw)] = raw

def put_u16(b, off, v):
    b[off:off+2] = struct.pack("<H", v)

def put_f32(b, off, v):
    b[off:off+4] = struct.pack("<f", v)

def build(uid, *, variant, material, ftype, detailed, rgba, weight, diameter,
          dry_t, dry_h, bed_type, bed_t, hot_max, hot_min, xcam, nozzle,
          tray_uid_bytes, spool_width, prod_date, short_date, length_m,
          fmt_id=0, color_count=0, second_abgr=None, garbage_type=False,
          keys=None):
    blocks = [blk() for _ in range(64)]
    keys = keys if keys is not None else derive_keys(uid)

    blocks[0][0:4] = uid
    blocks[0][4:16] = bytes([0x88,0x04,0x00,0x47,0x08,0x04,0x00,0x62,0x63,0x64,0x65,0x66])

    put_str(blocks[1], 0, variant, 8)
    put_str(blocks[1], 8, material, 8)
    if garbage_type:
        # Neither name is readable, which is what a physically damaged tag looks like:
        # it still unlocks, but nothing identifies the filament.
        blocks[2][0:16] = bytes([0x7f,0x01,0xe2,0x99,0x00,0x13,0xff,0xa0,0x11,0x22,0x33,0x44,0x55,0x66,0x77,0x88])
        blocks[4][0:16] = bytes([0xc3,0xbe,0x00,0x91,0x4d,0x5e,0x6f,0x70,0x81,0x92,0xa3,0xb4,0xc5,0xd6,0xe7,0xf8])
    else:
        put_str(blocks[2], 0, ftype, 16)
        put_str(blocks[4], 0, detailed, 16)

    blocks[5][0:4] = bytes(rgba)
    put_u16(blocks[5], 4, weight)
    put_f32(blocks[5], 8, diameter)

    put_u16(blocks[6], 0, dry_t)
    put_u16(blocks[6], 2, dry_h)
    put_u16(blocks[6], 4, bed_type)
    put_u16(blocks[6], 6, bed_t)
    put_u16(blocks[6], 8, hot_max)
    put_u16(blocks[6], 10, hot_min)

    blocks[8][0:12] = xcam
    put_f32(blocks[8], 12, nozzle)

    blocks[9][0:16] = tray_uid_bytes
    put_u16(blocks[10], 4, spool_width)
    put_str(blocks[12], 0, prod_date, 16)
    put_str(blocks[13], 0, short_date, 16)
    put_u16(blocks[14], 4, length_m)

    put_u16(blocks[16], 0, fmt_id)
    put_u16(blocks[16], 2, color_count)
    if second_abgr:
        blocks[16][4:8] = bytes(second_abgr)

    # sector trailers: derived key A, Bambu access bits, zero key B
    for sector in range(16):
        t = blocks[sector * 4 + 3]
        t[0:6] = keys[sector]
        t[6:10] = bytes([0x87,0x87,0x87,0x69])
        t[10:16] = bytes(6)

    # sectors 10-15 carry the RSA-2048 signature
    sig = hashlib.sha256(uid + b"sig").digest()
    for sector in range(10, 16):
        for b in range(3):
            i = sector * 4 + b
            blocks[i][0:16] = hashlib.sha256(sig + bytes([i])).digest()[:16]
    return blocks

def write(path, blocks, header):
    with open(path, "w") as f:
        f.write(header)
        for i, b in enumerate(blocks):
            f.write("%02d: %s\n" % (i, b.hex().upper()))

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "tagdumps")
os.makedirs(OUT, exist_ok=True)

uid_a = bytes.fromhex("A1B2C3D4")
blocks = build(
    uid_a,
    variant="A00-K0", material="GFA00", ftype="PLA", detailed="PLA Basic",
    rgba=[0xFF,0xFF,0xFF,0xFF], weight=1000, diameter=1.75,
    dry_t=55, dry_h=8, bed_type=1, bed_t=35, hot_max=230, hot_min=190,
    xcam=bytes([0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0C]), nozzle=0.4,
    tray_uid_bytes=b"0123456789ABCDEF", spool_width=6625,
    prod_date="2024_10_05_14_32", short_date="24_10_05", length_m=330,
)
write(OUT + "/pla_basic_jade_white.txt", blocks,
      "# Synthetic Bambu Lab Mifare Classic 1K dump, PLA Basic, built to the\n"
      "# Bambu Research Group tag spec. 64 blocks of 16 bytes, hex per line.\n"
      "# Tag UID A1B2C3D4. Sector trailers hold the UID-derived Key A.\n")

uid_b = bytes.fromhex("0F1E2D3C")
blocks_b = build(
    uid_b,
    variant="B50-B0", material="GFB50", ftype="ABS", detailed="ABS Filament",
    rgba=[0x1A,0x2B,0x3C,0xFF], weight=1000, diameter=1.75,
    dry_t=80, dry_h=12, bed_type=2, bed_t=95, hot_max=280, hot_min=240,
    xcam=bytes(12), nozzle=0.0,
    tray_uid_bytes=bytes.fromhex("6A1C2E3F4D5B60718293A4B5C6D7E8F9"), spool_width=0,
    prod_date="2025_01_17_09_05", short_date="25_01_17", length_m=0,
    fmt_id=2, color_count=2, second_abgr=[0xFF,0x99,0x66,0x33],
)
write(OUT + "/abs_dual_color_binary_trayuid.txt", blocks_b,
      "# Synthetic Bambu dump exercising the awkward cases: a tray UID that is raw\n"
      "# binary rather than ASCII, a second colour in block 16, and zeroed optional\n"
      "# fields (nozzle diameter, spool width, filament length). Tag UID 0F1E2D3C.\n")

uid_c = bytes.fromhex("DEADBEEF")
blocks_c = build(
    uid_c,
    variant="A00-K0", material="GFA00", ftype="PLA", detailed="PLA Basic",
    rgba=[0xFF,0xFF,0xFF,0xFF], weight=1000, diameter=1.75,
    dry_t=55, dry_h=8, bed_type=1, bed_t=35, hot_max=230, hot_min=190,
    xcam=bytes(12), nozzle=0.4, tray_uid_bytes=b"0123456789ABCDEF", spool_width=6625,
    prod_date="2024_10_05_14_32", short_date="24_10_05", length_m=330,
    garbage_type=True,
)
write(OUT + "/corrupt_filament_type.txt", blocks_c,
      "# Authenticates with the derived keys, but neither block 2 nor block 4 holds a\n"
      "# readable filament name. Used to prove a damaged tag is reported as such rather\n"
      "# than shown as a blank spool.\n")

uid_d = bytes.fromhex("11223344")
blocks_d = build(
    uid_d,
    variant="A00-K0", material="GFA00", ftype="PLA", detailed="PLA Basic",
    rgba=[0,0,0,0], weight=0, diameter=0.0,
    dry_t=0, dry_h=0, bed_type=0, bed_t=0, hot_max=0, hot_min=0,
    xcam=bytes(12), nozzle=0.0, tray_uid_bytes=bytes(16), spool_width=0,
    prod_date="", short_date="", length_m=0,
    keys=[bytes([0xFF]*6) for _ in range(16)],
)
write(OUT + "/third_party_default_keys.txt", blocks_d,
      "# A non-Bambu Mifare Classic 1K tag still on the factory default key\n"
      "# FFFFFFFFFFFF. The derived Bambu keys must fail against this.\n")

print("expected keys for A1B2C3D4:")
for i, k in enumerate(derive_keys(uid_a)):
    print("  sector %2d: %s" % (i, k.hex().upper()))
print("expected keys for 0F1E2D3C sector 0:", derive_keys(uid_b)[0].hex().upper())
print("5-byte uid 0102030405 sector 0:", derive_keys(bytes.fromhex("0102030405"))[0].hex().upper())
