"""Verify the actual JNI definitions in a release APK, independent of R8 mapping formatting.

DEX layout: https://source.android.com/docs/core/runtime/dex-format
Only the three voice bridge classes are decoded. Field/method references alone do not count:
we require their definitions in class_data_item and the correct JNI descriptors/access flags.
"""
import re
import struct
import sys
import zipfile
from pathlib import Path

BRIDGES = {"Lcom/sun/jna/Pointer;", "Lcom/sun/jna/Native;", "Lorg/vosk/LibVosk;"}


def definitions(data: bytes) -> dict:
    if data[:8] not in [f"dex\n{version}\0".encode() for version in ("035", "037", "038", "039", "040")]:
        raise ValueError("Unsupported DEX format; update the verifier before accepting this APK")
    if struct.unpack_from("<I", data, 0x28)[0] != 0x12345678:
        raise ValueError("Unsupported DEX byte order")

    def uint(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
        raise ValueError("Invalid DEX variable-length integer")

    def string(index):
        offset = uint(uint(0x3C) + index * 4)
        _, offset = uleb(offset)
        # Bridge descriptors/member names are ASCII. Other strings never need decoding.
        return data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace")

    def type_name(index):
        return string(uint(uint(0x44) + index * 4))

    def prototype(index):
        offset = uint(0x4C) + index * 12
        result, arguments = uint(offset + 4), uint(offset + 8)
        params = "" if not arguments else "".join(
            type_name(struct.unpack_from("<H", data, arguments + 4 + i * 2)[0])
            for i in range(uint(arguments))
        )
        return f"({params}){type_name(result)}"

    found = {}
    for i in range(uint(0x60)):
        offset = uint(0x64) + i * 32
        owner = type_name(uint(offset))
        if owner not in BRIDGES:
            continue
        position = uint(offset + 24)
        fields, methods = [], []
        if position:
            counts = []
            for _ in range(4):
                count, position = uleb(position)
                counts.append(count)
            for count in counts[:2]:
                index = 0
                for _ in range(count):
                    delta, position = uleb(position)
                    access, position = uleb(position)
                    index += delta
                    declaring, field_type, name = struct.unpack_from("<HHI", data, uint(0x54) + index * 8)
                    if type_name(declaring) != owner:
                        raise ValueError("DEX field definition belongs to another class")
                    fields.append((string(name), type_name(field_type), access))
            for count in counts[2:]:
                index = 0
                for _ in range(count):
                    delta, position = uleb(position)
                    access, position = uleb(position)
                    _, position = uleb(position)  # code_off; native methods have no bytecode.
                    index += delta
                    declaring, proto, name = struct.unpack_from("<HHI", data, uint(0x5C) + index * 8)
                    if type_name(declaring) != owner:
                        raise ValueError("DEX method definition belongs to another class")
                    methods.append((string(name), prototype(proto), access))
        if owner in found:
            raise ValueError(f"Duplicate bridge definition: {owner}")
        found[owner] = (fields, methods)
    return found


def verify_bridges(bridges: dict) -> None:
    for owner in BRIDGES:
        if owner not in bridges:
            raise ValueError(f"Native bridge class removed or renamed: {owner}")
    fields = bridges["Lcom/sun/jna/Pointer;"][0]
    if not any(name == "peer" and kind == "J" and not flags & 0x8 for name, kind, flags in fields):
        raise ValueError("APK is missing the instance long field Pointer.peer required by JNA initIDs")
    for owner, name, signature in (
        ("Lcom/sun/jna/Native;", "initIDs", "()V"),
        ("Lorg/vosk/LibVosk;", "vosk_set_log_level", "(I)V"),
    ):
        if not any(n == name and proto == signature and flags & 0x100 and flags & 0x8
                   for n, proto, flags in bridges[owner][1]):
            raise ValueError(f"Native static method removed, renamed or changed: {owner}.{name}{signature}")


def verify_apk(path: Path) -> None:
    bridges = {}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if re.fullmatch(r"classes(?:\d+)?\.dex", name):
                for owner, members in definitions(apk.read(name)).items():
                    if owner in bridges:
                        raise ValueError(f"Duplicate native bridge across DEX files: {owner}")
                    bridges[owner] = members
        verify_bridges(bridges)
        abis = {name.split("/")[1] for name in apk.namelist() if re.fullmatch(r"lib/[^/]+/[^/]+\.so", name)}
        if not abis:
            raise ValueError("APK contains no native libraries")
        for abi in abis:
            for library in ("libjnidispatch.so", "libvosk.so"):
                if f"lib/{abi}/{library}" not in apk.namelist():
                    raise ValueError(f"Missing {abi}/{library}")
    print(f"Voice JNI definitions and libraries preserved: {path}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Usage: verify-voice-native-apk.py APK_FILE [...]")
    for argument in sys.argv[1:]:
        verify_apk(Path(argument))
