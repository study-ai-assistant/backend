#!/usr/bin/env python3
"""Measure PDF worker on external documents; never downloads or calls an LLM."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument("documents", type=Path, help="Directory with sources.json and external PDF files")
parser.add_argument("--classpath", type=Path, default=Path("target/pdf-classpath.txt"))
parser.add_argument("--jar", type=Path, help="Use the packaged server JAR, like the production worker")
parser.add_argument("--repeats", type=int, default=3)
args = parser.parse_args()
classpath = None if args.jar else str(Path("target/classes").resolve()) + ":" + args.classpath.read_text().strip()
entry = ["-jar", str(args.jar.resolve())] if args.jar else ["-cp", classpath, "ru.studyassistant.backend.BackendApplication"]
sources = json.loads((args.documents / "sources.json").read_text())
measurements = []
for source in sources:
    pdf = (args.documents / (source["name"] + ".pdf")).resolve()
    digest = hashlib.sha256(pdf.read_bytes()).hexdigest()
    if digest != source["sha256"]:
        raise ValueError("Source changed: " + source["name"])
    info = subprocess.check_output(["pdfinfo", str(pdf)], text=True)
    pages = int(next(line.split(":", 1)[1].strip() for line in info.splitlines() if line.startswith("Pages:")))
    for mode, max_text in [("policy", 10000), ("quality", 1000000)]:
        for repeat in range(args.repeats):
            with tempfile.TemporaryDirectory(prefix="pdf-benchmark-") as temporary:
                root = Path(temporary)
                output = root / "result.bin"
                command = ["java", "-Xmx128m",
                           "-XX:MaxMetaspaceSize=96m", "-XX:-UsePerfData", "-Dfile.encoding=UTF-8",
                           "-Duser.home=" + str(root), "-Djava.io.tmpdir=" + str(root),
                           *entry, "--internal-pdf-worker", str(pdf), str(output), "50", str(max_text)]
                started = time.perf_counter()
                process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                           env={"PATH": os.environ["PATH"]})
                try:
                    while True:
                        pid, status_code, usage = os.wait4(process.pid, os.WNOHANG)
                        if pid:
                            process.returncode = os.waitstatus_to_exitcode(status_code)
                            break
                        if time.perf_counter() - started > 15:
                            raise TimeoutError("Worker deadline exceeded")
                        time.sleep(0.002)
                finally:
                    if process.returncode is None:
                        process.kill()
                        _, status_code, usage = os.wait4(process.pid, 0)
                        process.returncode = os.waitstatus_to_exitcode(status_code)
                wall_ms = (time.perf_counter() - started) * 1000
                if process.returncode:
                    raise RuntimeError("Worker failed: " + source["name"])
                data = output.read_bytes()
                status, length = struct.unpack(">ii", data[:8])
                text = data[8:8 + length].decode("utf-8")
                parse_ns = struct.unpack(">q", data[8 + length:16 + length])[0]
                if mode == "quality" and repeat == 0 and status == 200:
                    (args.documents / (source["name"] + ".txt")).write_text(text, encoding="utf-8")
                item = dict(name=source["name"], mode=mode, repeat=repeat + 1, pages=pages,
                            sha256=digest, file_bytes=pdf.stat().st_size, status=status,
                            chars=len(text) if status == 200 else None, parse_ms=round(parse_ns / 1e6, 2),
                            process_ms=round(wall_ms, 2), max_rss_kib=usage.ru_maxrss)
                measurements.append(item)
                print(json.dumps(item), flush=True)
(args.documents / "measurements.json").write_text(json.dumps(measurements, indent=2) + "\n")
