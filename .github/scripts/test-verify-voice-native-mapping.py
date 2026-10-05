import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("voice_mapping", Path(__file__).with_name("verify-voice-native-mapping.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
GOOD = """com.sun.jna.Pointer -> com.sun.jna.Pointer:
    long peer -> peer
com.sun.jna.Native -> com.sun.jna.Native:
org.vosk.LibVosk -> org.vosk.LibVosk:
    12:12:void vosk_set_log_level(int):31:31 -> vosk_set_log_level
"""


class VoiceMappingTest(unittest.TestCase):
    def test_preserved_bridge(self):
        module.verify(GOOD)

    def test_renamed_or_removed_bridge_is_rejected(self):
        for broken in (
            GOOD.replace("long peer -> peer", "long peer -> a"),
            GOOD.replace("    long peer -> peer\n", ""),
            GOOD.replace("Native -> com.sun.jna.Native", "Native -> a.b"),
            GOOD.replace("Pointer -> com.sun.jna.Pointer", "Pointer -> a.c"),
            GOOD.replace("-> vosk_set_log_level", "-> r"),
            GOOD.replace("org.vosk.LibVosk -> org.vosk.LibVosk:", "org.vosk.LibVosk -> v4.I:"),
        ):
            with self.subTest(mapping=broken), self.assertRaises(ValueError):
                module.verify(broken)


if __name__ == "__main__":
    unittest.main()
