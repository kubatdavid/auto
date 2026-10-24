#!/usr/bin/env python3
"""Import drive logs recorded by the laptop prototype (car/logs/*.csv + *.codes.json) via the API.

Usage: AUTO_TOKEN=... python3 import_csv.py ../../logs/*.csv
Idempotent: the trip UUID is derived from the file name and samples are keyed by seq.
"""
import csv
import gzip
import json
import os
import sys
import urllib.request
import uuid
from pathlib import Path

API = os.environ.get("AUTO_API", "https://auto.abhishekam.cz")
TOKEN = os.environ["AUTO_TOKEN"]
FIELDS = ["speed", "rpm", "maf", "throttle", "coolant", "load", "stft", "ltft", "fuelsys", "iat", "timing",
          "o2_down", "volts", "fuel_lph"]


def call(method: str, path: str, body: dict | None = None) -> dict:
    data = gzip.compress(json.dumps(body).encode()) if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json", "Content-Encoding": "gzip",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def num(v: str | None) -> float | None:
    return float(v) if v not in (None, "") else None


for name in sys.argv[1:]:
    path = Path(name)
    rows = list(csv.DictReader(path.open()))
    if not rows:
        continue
    trip = str(uuid.uuid5(uuid.NAMESPACE_URL, "auto-laptop-log:" + path.name))
    samples = []
    for seq, r in enumerate(rows):
        s = {"seq": seq, "t": float(r["time"])}
        s.update({k: num(r.get(k)) for k in FIELDS if num(r.get(k)) is not None})
        samples.append(s)
    call("PUT", f"/api/v1/trips/{trip}", {"started_at": samples[0]["t"]})
    for i in range(0, len(samples), 2000):
        call("POST", f"/api/v1/trips/{trip}/samples", {"samples": samples[i:i + 2000]})
    codes_path = path.with_suffix(".codes.json")
    if codes_path.exists():
        c = json.loads(codes_path.read_text())
        call("POST", f"/api/v1/trips/{trip}/dtc", {"t": c.get("read_at", samples[0]["t"]), "mil": c.get("mil"),
                                                     "stored": c.get("stored", []), "pending": c.get("pending", []),
                                                     "freeze": c.get("freeze", {})})
    res = call("POST", f"/api/v1/trips/{trip}/finish", {"ended_at": samples[-1]["t"]})
    print(path.name, trip, res["summary"])
