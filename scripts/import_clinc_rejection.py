"""Reproduce the narrow English subset from an explicit local CLINC checkout; no network."""
import hashlib
import json
import pathlib
import subprocess
import sys
import unicodedata

REVISION = "828f8093932c8fe6ca7936c3d2e52903b1c523de"
MAPPING = {"greeting": "chat", "thank_you": "chat", "tell_joke": "chat",
           "change_volume": "device", "book_flight": "__reject__", "book_hotel": "__reject__",
           "restaurant_reservation": "__reject__", "transfer": "__reject__"}


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--verify":
        destination = pathlib.Path(sys.argv[2])
        manifest = json.loads((destination / "provenance.json").read_text())
        raw = (destination / "rejection.tsv").read_bytes()
        if manifest["revision"] != REVISION or manifest["mapping"] != MAPPING or manifest["subset_sha256"] != hashlib.sha256(raw).hexdigest():
            raise ValueError("Subset provenance mismatch")
        if len(raw.decode("utf-8").splitlines()) - 1 != manifest["rows"]:
            raise ValueError("Subset row count mismatch")
        print("Pinned CLINC subset integrity verified")
        return
    source, destination, original = map(pathlib.Path, sys.argv[1:])
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if revision != REVISION:
        raise ValueError("Unexpected upstream revision")
    raw = subprocess.check_output(["git", "-C", str(source), "show", f"{REVISION}:data/data_full.json"])
    data = json.loads(raw)
    rows = ["split\tid\tlabel\tupstream_intent\ttext"]
    def canonical(text):
        text = unicodedata.normalize("NFKC", text).lower()
        text = "".join(c for c in text if c != "\u0640" and not "\u064b" <= c <= "\u065f" and c != "\u0670")
        text = text.translate(str.maketrans("\u0622\u0623\u0625\u0649", "\u0627\u0627\u0627\u064a"))
        return " ".join("".join(c if c.isalnum() else " " for c in text).split())
    seen = {canonical(line.split("\t")[4]) for line in original.read_text().splitlines()[1:]}
    excluded = []
    for upstream, target, start, limit in [("train", "train", 0, 25), ("val", "development", 0, 5),
                                            ("val", "calibration", 5, 5), ("test", "test", 0, 10)]:
        for intent, label in MAPPING.items():
            matches = [(index, text) for index, (text, found) in enumerate(data[upstream]) if found == intent]
            selected = 0
            for position, (index, text) in enumerate(matches[start:], start):
                # Validation halves remain disjoint: development never reaches calibration indices.
                if upstream == "val" and target == "development" and position >= 5:
                    break
                key = canonical(text)
                if key in seen:
                    excluded.append(f"{target}:clinc-{upstream}-{index}")
                    continue
                if any(c in text for c in "\t\r\n"):
                    raise ValueError("Malformed selected text")
                seen.add(key)
                rows.append(f"{target}\tclinc-{upstream}-{index}\t{label}\t{intent}\t{text}")
                selected += 1
                if selected == limit:
                    break
            if selected == 0:
                raise ValueError("Missing upstream samples")
    destination.mkdir(parents=True, exist_ok=True)
    subset = ("\n".join(rows) + "\n").encode("utf-8")
    (destination / "rejection.tsv").write_bytes(subset)
    (destination / "LICENSE").write_bytes(subprocess.check_output(["git", "-C", str(source), "show", f"{REVISION}:LICENSE"]))
    manifest = dict(repository="https://github.com/clinc/oos-eval", revision=REVISION,
                    source="data/data_full.json", source_sha256=hashlib.sha256(raw).hexdigest(),
                    mapping=MAPPING, rows=len(rows) - 1, language="English only", excluded_duplicate_ids=excluded,
                    subset_sha256=hashlib.sha256(subset).hexdigest(),
                    original_corpus_sha256=hashlib.sha256(original.read_bytes()).hexdigest(),
                    note="Subset/remapping for Omni's narrow five-domain experiment; not the CLINC150 benchmark.")
    (destination / "provenance.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
