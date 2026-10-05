import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("voice_apk", Path(__file__).with_name("verify-voice-native-apk.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def dex_fixture(peer="peer", kind="J", declared=True, native=True, static=True, owners=None):
    """Minimal DEX tables, including an unused field reference when declared=False."""
    owners = module.BRIDGES if owners is None else owners
    types = sorted(module.BRIDGES | {"I", "J", "V", "I" if kind == "I" else "J"})
    strings = sorted(set(types + [peer, "initIDs", "vosk_set_log_level"]))
    string_id = {value: i for i, value in enumerate(strings)}
    type_id = {value: i for i, value in enumerate(types)}
    data = bytearray(112)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<II", data, 0x24, 112, 0x12345678)

    def table(header, count, width):
        offset = len(data)
        data.extend(bytes(count * width))
        struct.pack_into("<II", data, header, count, offset)
        return offset

    string_table = table(0x38, len(strings), 4)
    type_table = table(0x40, len(types), 4)
    proto_table = table(0x48, 2, 12)
    field_table = table(0x50, 1, 8)
    method_table = table(0x58, 2, 8)
    class_table = table(0x60, len(owners), 32)
    for i, value in enumerate(strings):
        struct.pack_into("<I", data, string_table + i * 4, len(data))
        data.extend(bytes([len(value)]) + value.encode() + b"\0")
    for i, value in enumerate(types):
        struct.pack_into("<I", data, type_table + i * 4, string_id[value])
    while len(data) % 4:
        data.append(0)
    arguments = len(data)
    data.extend(struct.pack("<IH", 1, type_id["I"]))
    struct.pack_into("<III", data, proto_table, string_id["V"], type_id["V"], 0)
    struct.pack_into("<III", data, proto_table + 12, string_id["V"], type_id["V"], arguments)
    struct.pack_into("<HHI", data, field_table, type_id["Lcom/sun/jna/Pointer;"], type_id[kind], string_id[peer])
    for i, (owner, name) in enumerate((("Lcom/sun/jna/Native;", "initIDs"), ("Lorg/vosk/LibVosk;", "vosk_set_log_level"))):
        struct.pack_into("<HHI", data, method_table + i * 8, type_id[owner], i, string_id[name])
    # Native/static flags need two-byte ULEB128 encoding when native=True.
    flags = (0x100 if native else 0) | (0x8 if static else 0) | 0x1
    encoded_flags = bytes([(flags & 127) | 128, flags >> 7]) if flags >= 128 else bytes([flags])
    for i, owner in enumerate(sorted(owners)):
        position = len(data)
        if owner == "Lcom/sun/jna/Pointer;":
            data.extend(bytes([0, int(declared), 0, 0]))
            if declared:
                data.extend(b"\0\4")
        else:
            method_index = 0 if owner == "Lcom/sun/jna/Native;" else 1
            data.extend(bytes([0, 0, 1, 0, method_index]) + encoded_flags + b"\0")
        struct.pack_into("<8I", data, class_table + i * 32, type_id[owner], 1, 0xFFFFFFFF, 0, 0xFFFFFFFF, 0, position, 0)
    struct.pack_into("<I", data, 0x20, len(data))
    return bytes(data)


class VoiceApkTest(unittest.TestCase):
    def test_native_definitions_survive_without_any_mapping_file(self):
        module.verify_bridges(module.definitions(dex_fixture()))

    def test_field_reference_does_not_substitute_for_a_definition(self):
        with self.assertRaisesRegex(ValueError, "Pointer.peer"):
            module.verify_bridges(module.definitions(dex_fixture(declared=False)))

    def test_renamed_or_wrong_type_field_and_non_native_or_non_static_methods_fail(self):
        for changes in ({"peer": "a"}, {"kind": "I"}, {"native": False}, {"static": False}, {"owners": {"Lcom/sun/jna/Pointer;"}}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                module.verify_bridges(module.definitions(dex_fixture(**changes)))

    def test_multidex_and_native_libraries(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "release.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", dex_fixture(owners={"Lcom/sun/jna/Pointer;"}))
                archive.writestr("classes2.dex", dex_fixture(owners=module.BRIDGES - {"Lcom/sun/jna/Pointer;"}))
                for name in ("libjnidispatch.so", "libvosk.so"):
                    archive.writestr(f"lib/arm64-v8a/{name}", b"fixture")
            module.verify_apk(apk)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/x86_64/libvosk.so", b"fixture")
            with self.assertRaisesRegex(ValueError, "libjnidispatch"):
                module.verify_apk(apk)

    def test_unsupported_dex_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "Unsupported DEX"):
            module.definitions(b"not dex")


if __name__ == "__main__":
    unittest.main()
