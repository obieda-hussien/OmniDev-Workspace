"""Reject release shrinker output that breaks JNA's JNI field lookup or Vosk mapping."""
import re
import sys
from pathlib import Path


def verify(mapping: str) -> None:
    classes = {}
    current = None
    for line in mapping.splitlines():
        if line.startswith("#"):
            continue
        header = re.fullmatch(r"(\S+) -> (\S+):", line)
        if header:
            current = header[1]
            classes[current] = (header[2], [])
        elif current and line.startswith(" "):
            classes[current][1].append(line.strip())
    for name in ("com.sun.jna.Pointer", "com.sun.jna.Native", "org.vosk.LibVosk"):
        if name not in classes or classes[name][0] != name:
            raise ValueError(f"Native bridge class removed or renamed: {name}")
    if "long peer -> peer" not in classes["com.sun.jna.Pointer"][1]:
        raise ValueError("JNA Pointer.peer removed or renamed; jnidispatch initIDs will crash")
    if not any(re.search(r"\bvosk_set_log_level\(int\)(?::\d+)* -> vosk_set_log_level$", member)
               for member in classes["org.vosk.LibVosk"][1]):
        raise ValueError("Vosk native function mapping removed or renamed")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Usage: verify-voice-native-mapping.py MAPPING_FILE [...]")
    for argument in sys.argv[1:]:
        verify(Path(argument).read_text())
        print(f"Voice native bridge preserved: {argument}")
