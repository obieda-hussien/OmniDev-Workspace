"""Export deterministic assets for the Android JVM specialist inference runtime."""
import csv
import gzip
import json
import struct
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ASSET = HERE.parents[2] / "app/src/main/assets/omni-models/specialists-v1.bin.gz"


def export(path):
    with np.load(HERE / "encoder.npz", allow_pickle=False) as encoder:
        table = encoder["encoder"]
        embedding_scale = encoder["enc_scale"]
    scoped = json.loads((HERE / "catalog-defensive.json").read_text(encoding="utf-8"))
    summaries = json.loads((HERE / "tool-explanations.json").read_text(encoding="utf-8"))
    with (HERE / "tool-guidance.tsv").open(encoding="utf-8") as stream:
        guidance = {row["key"]: row for row in csv.DictReader(stream, delimiter="\t")}

    def strings(blob, strings):
        for value in strings:
            encoded = value.encode("utf-8")
            blob.write(struct.pack(">H", len(encoded)) + encoded)

    def head(blob, file, transpose=False, exclude=()):
        with np.load(HERE / file, allow_pickle=False) as data:
            labels = data["labels"].tolist()
            selected = [i for i, label in enumerate(labels) if label not in exclude]
            matrix = data["w"] if "w" in data.files else data["weights"]
            if transpose:
                matrix = matrix.T
            scales = data["scale"][selected]
            bias = data["bias"][selected] if "bias" in data.files else np.zeros(len(selected))
            blob.write(struct.pack(">H", len(selected)))
            strings(blob, (labels[i] for i in selected))
            blob.write(matrix[:, selected].astype("i1").tobytes(order="C"))
            blob.write(scales.astype(">f4").tobytes())
            blob.write(bias.astype(">f4").tobytes())
            return [labels[i] for i in selected]

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as blob:
        blob.write(b"OMS1")
        blob.write(table.astype("i1").tobytes(order="C"))
        blob.write(embedding_scale.astype(">f4").tobytes())
        head(blob, "catalog-advisor.npz", exclude=scoped)
        operations = head(blob, "tool-advisor.npz", transpose=True)
        with np.load(HERE / "tool-advisor.npz", allow_pickle=False) as data:
            blob.write(struct.pack(">f", float(data["threshold"])))
        for label in operations:
            row = guidance[label]
            strings(blob, [row["usage"], row["required"]])
        explanations = head(blob, "tool-explanations.npz", transpose=True)
        for label in explanations:
            row = summaries[label]
            strings(blob, [row.get("summary_ar", ""), row.get("summary_en", "")])


if __name__ == "__main__":
    export(ASSET)
