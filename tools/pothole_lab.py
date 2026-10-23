"""
Pothole lab: pull research rides off the phone and check how well pothole detection works.

    python tools/pothole_lab.py pull          # copy files/research/* from the phone into research_data/
    python tools/pothole_lab.py analyse [id]  # analyse one ride (default: newest)

What "analyse" does:
  * runs the same bump detector as the app and says which rider marks it caught or missed
  * lists every bump it finds, classed as pothole or speed breaker
  * sweeps the floor and prominence settings to show the trade-off
  * writes an SVG chart per clip into research_data/<id>/charts/

Pure Python 3, no extra packages. Needs adb (Android SDK platform-tools) for "pull".
"""
import csv
import json
import os
import subprocess
import sys

G = 9.81
PKG = "com.invisiblejourney"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "research_data")
ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe") if os.name == "nt" else "adb"



def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, check=False)


def pull():
    out = adb("shell", "run-as", PKG, "find", "files/research", "-type", "f").stdout.decode()
    files = [f.strip() for f in out.splitlines() if f.strip()]
    if not files:
        sys.exit("No research files on the phone (is it plugged in, and have you done a real ride?)")
    for f in files:
        local = os.path.join(ROOT, os.path.relpath(f, "files/research"))
        os.makedirs(os.path.dirname(local), exist_ok=True)
        data = adb("exec-out", "run-as", PKG, "cat", f).stdout
        with open(local, "wb") as fh:
            fh.write(data)
    # Journey JSONs too, for marks/track context.
    out = adb("shell", "run-as", PKG, "ls", "files/journeys").stdout.decode().split()
    for j in out:
        jid = j.replace(".journey.json", "")
        d = os.path.join(ROOT, jid)
        if os.path.isdir(d):
            with open(os.path.join(d, "journey.json"), "wb") as fh:
                fh.write(adb("exec-out", "run-as", PKG, "cat", "files/journeys/" + j).stdout)
    print(f"Pulled {len(files)} files into {os.path.abspath(ROOT)}")


def read_imu(path):
    rows = []
    with open(path, newline="") as fh:
        for r in csv.DictReader(line for line in fh if not line.startswith("#")):
            try:
                rows.append((int(r["t_ms"]), float(r["vertical"]), float(r["speed_kmh"])))
            except (ValueError, KeyError):
                pass
    return rows


def read_events(path):
    with open(path, newline="") as fh:
        return [r for r in csv.DictReader(fh)]


def svg_chart(rows, centre_t, title, path):
    if not rows:
        return
    w, h, pad = 900, 260, 30
    t0, t1 = rows[0][0], rows[-1][0]
    vmax = max(2.0, max(abs(v) / G for _, v, _ in rows))
    x = lambda t: pad + (t - t0) / max(1, t1 - t0) * (w - 2 * pad)
    y = lambda g: h / 2 - g / vmax * (h / 2 - pad)
    pts = " ".join(f"{x(t):.1f},{y(v / G):.1f}" for t, v, _ in rows)
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" font-family="sans-serif" font-size="12">',
        f'<rect width="{w}" height="{h}" fill="#121212"/>',
        f'<text x="{pad}" y="18" fill="#FFC72C">{title} (vertical accel, g; dashed = tap / detection)</text>',
        f'<line x1="{pad}" x2="{w - pad}" y1="{y(0)}" y2="{y(0)}" stroke="#444"/>',
    ]
    for g in (1, -1):
        lines.append(f'<line x1="{pad}" x2="{w - pad}" y1="{y(g)}" y2="{y(g)}" stroke="#333" stroke-dasharray="2,4"/>')
        lines.append(f'<text x="2" y="{y(g) + 4}" fill="#888">{g:+d}g</text>')
    lines.append(f'<line x1="{x(centre_t)}" x2="{x(centre_t)}" y1="{pad}" y2="{h - pad}" stroke="#E53935" stroke-dasharray="6,4"/>')
    lines.append(f'<polyline points="{pts}" fill="none" stroke="#FFC72C" stroke-width="1"/>')
    lines.append("</svg>")
    with open(path, "w") as fh:
        fh.write("\n".join(lines))




# Rider marks are matched to detections from MARK_BEFORE_MS before the tap (people tap after
# the hit) to MARK_AFTER_MS after it (or they tap as they see it coming).
MARK_BEFORE_MS = 6000
MARK_AFTER_MS = 3000
# Taps jolt the phone: ignore detections whose peak is this close after a tap.
TAP_SHADOW_MS = 250

# Mirrors BumpDetector / BumpClassifier in app/src/main/java/com/invisiblejourney/domain/processor/Processors.kt
FLOOR_G = {"CAR": 0.45, "POCKET": 0.8, "HANDLEBAR": 1.2, "BAG": 0.7}
MIN_PROMINENCE = {"CAR": 5.0}  # others: 6.0
MIN_SPEED_KMH = 5.0
QUIET_MS = 800
MAX_EVENT_MS = 3000
MIN_WIDTH_MS = 25
BG_TAU_MS = 5000.0


def detect(rows, floor_g, min_prom=6.0):
    """Port of BumpDetector.onSample. rows = [(t_ms, vertical_ms2, speed_kmh)]."""
    bumps, bg2, last_t, pre, ev = [], 0.05 ** 2, 0, [], None
    for t, vert, speed in rows:
        g = abs(vert) / G
        dt = 10 if last_t == 0 else min(max(t - last_t, 1), 100)
        last_t = t
        bg = bg2 ** 0.5
        bg2 += dt / (BG_TAU_MS + dt) * (g * g - bg2)
        if ev is None:
            if g >= max(floor_g, 4 * bg) and speed >= MIN_SPEED_KMH:
                ev = dict(s=pre + [(t, g)], start=t, loud=t, minspd=speed, bg=bg)
                pre = []
                continue
            pre.append((t, g))
            while pre and t - pre[0][0] > 150:
                pre.pop(0)
            continue
        ev["s"].append((t, g))
        ev["minspd"] = min(ev["minspd"], speed)
        if g >= max(floor_g * 0.5, 2.5 * ev["bg"]):
            ev["loud"] = t
        if t - ev["loud"] < QUIET_MS and t - ev["start"] < MAX_EVENT_MS:
            continue
        s, e = ev["s"], ev
        ev = None
        pt, pg = max(s, key=lambda x: x[1])
        near = [x for x in s if abs(x[0] - pt) <= 150]
        step = (near[-1][0] - near[0][0]) / (len(near) - 1) if len(near) > 1 else 10
        width = sum(1 for x in near if x[1] >= pg / 2) * step
        prom = pg / max(e["bg"], 0.03)
        if pg >= floor_g and prom >= min_prom and width >= MIN_WIDTH_MS:
            bumps.append(dict(start=e["start"], peak_t=pt, end=t, peak=pg, prom=prom, width=width, minspd=e["minspd"]))
    return bumps


def shape(times, values):
    """Port of Spectrum.shape: (centroid Hz, share of energy in 0.5-4 Hz)."""
    import math
    n = len(values)
    if n < 16:
        return 0.0, 0.0
    dt = (times[-1] - times[0]) / 1000 / (n - 1)
    mean = sum(values) / n
    x = [(values[i] - mean) * (0.5 - 0.5 * math.cos(2 * math.pi * i / (n - 1))) for i in range(n)]
    total = low = weighted = 0.0
    f = 0.5
    while f <= 0.5 / dt:
        w = 2 * math.pi * f * dt
        re = sum(x[i] * math.cos(w * i) for i in range(n))
        im = sum(x[i] * math.sin(w * i) for i in range(n))
        p = re * re + im * im
        total += p
        weighted += p * f
        if f < 4:
            low += p
        f += 0.5
    return (weighted / total, low / total) if total else (0.0, 0.0)


def classify(b, rows, mount):
    """Port of BumpClassifier: slow heave (low frequency) = speed breaker, sharp hit = pothole."""
    clip = [r for r in rows if abs(r[0] - b["peak_t"]) <= 1000]
    b["centroid"], b["low"] = shape([r[0] for r in clip], [r[1] / G for r in clip])
    slow = b["minspd"] <= (30 if mount == "CAR" else 25)
    return "hump" if slow and (b["centroid"] <= 3.5 or b["low"] >= 0.8) else "pothole"


def mark_groups(events):
    """Taps within 4 s of each other are one event (people tap repeatedly)."""
    groups = []
    for e in events:
        if not e["kind"].startswith("mark"):
            continue
        kind = "hump" if e["kind"] == "mark_hump" else "pothole"
        t = int(e["t_ms"])
        if groups and t - groups[-1]["taps"][-1] < 4000 and groups[-1]["kind"] == kind:
            groups[-1]["taps"].append(t)
        else:
            groups.append(dict(kind=kind, taps=[t]))
    return groups


def score(bumps, groups):
    taps = [t for g in groups for t in g["taps"]]
    usable = [b for b in bumps if not any(0 <= b["peak_t"] - t <= TAP_SHADOW_MS for t in taps)]
    caught = [any(g["taps"][0] - MARK_BEFORE_MS <= b["peak_t"] <= g["taps"][-1] + MARK_AFTER_MS for b in usable) for g in groups]
    unmatched = [b for b in usable if not any(g["taps"][0] - MARK_BEFORE_MS <= b["peak_t"] <= g["taps"][-1] + MARK_AFTER_MS for g in groups)]
    return caught, unmatched


def analyse(ride_id=None):
    rides = sorted((d for d in os.listdir(ROOT) if os.path.isfile(os.path.join(ROOT, d, "imu.csv"))),
                   key=lambda d: os.path.getmtime(os.path.join(ROOT, d, "imu.csv")))
    if not rides:
        sys.exit("No rides in research_data/. Run 'pull' first.")
    rid = ride_id or rides[-1]
    d = os.path.join(ROOT, rid)
    rows = read_imu(os.path.join(d, "imu.csv"))
    events = read_events(os.path.join(d, "events.csv"))
    mount = "unknown"
    jpath = os.path.join(d, "journey.json")
    if os.path.exists(jpath):
        with open(jpath, encoding="utf-8") as fh:
            mount = json.load(fh).get("mountType", "unknown")
    floor = FLOOR_G.get(mount, 0.8)
    rows_t = [r[0] for r in rows]
    rows_spd = [r[2] for r in rows]
    t0 = rows[0][0] if rows else 0
    dur = (rows[-1][0] - t0) / 1000 if rows else 0

    print(f"Ride {rid}")
    print(f"  phone position / vehicle: {mount}"
          + ("  (car suspension damps jolts: car thresholds do NOT carry over to a bike)" if mount == "CAR" else ""))
    print(f"  {len(rows)} samples over {dur / 60:.1f} min ({len(rows) / dur if dur else 0:.0f} Hz)")
    old = sum(1 for e in events if e["kind"].startswith("auto"))
    print(f"  the app detected {old} bumps on this ride (with the detector it had then)\n")

    groups = mark_groups(events)
    prom = MIN_PROMINENCE.get(mount, 6.0)
    bumps = detect(rows, floor, prom)
    for b in bumps:
        b["kind"] = classify(b, rows, mount)
    caught, unmatched = score(bumps, groups)

    print(f"Current detector (floor {floor} g, prominence >= {prom}):")
    print(f"  {len(bumps)} bumps: {sum(b['kind'] == 'pothole' for b in bumps)} potholes, "
          f"{sum(b['kind'] == 'hump' for b in bumps)} speed breakers")
    for i, (g, c) in enumerate(zip(groups, caught), 1):
        taps = ", ".join(f"{(t - t0) / 1000:.0f}s" for t in g["taps"])
        print(f"  marked {g['kind']:7s} #{i} (tap at {taps}): {'caught' if c else 'MISSED'}")
    print(f"  bumps with no mark nearby: {len(unmatched)} (false alarms, or real bumps nobody marked)\n")

    print("All detected bumps:")
    for b in bumps:
        near = [i + 1 for i, g in enumerate(groups) if g["taps"][0] - MARK_BEFORE_MS <= b["peak_t"] <= g["taps"][-1] + MARK_AFTER_MS]
        print(f"  {(b['peak_t'] - t0) / 1000:7.1f}s  {b['kind']:7s} peak {b['peak']:.2f} g  stands out {b['prom']:4.1f}x  "
              f"slowest {b['minspd']:3.0f} km/h  {b['centroid']:4.1f} Hz  {b['low']:4.0%} slow  "
              f"{'<- mark #' + ','.join(map(str, near)) if near else ''}")

    print("\nSweep (floor g / prominence -> marks caught, bumps with no mark):")
    floors = (0.35, 0.45, 0.55, 0.65) if mount == "CAR" else (0.6, 0.8, 1.0, 1.2, 1.5)
    for f in floors:
        for p in (4, 5, 6, 8):
            c, u = score(detect(rows, f, p), groups)
            print(f"  floor {f:.2f} / prominence {p:2d}: caught {sum(c)}/{len(groups)}, no-mark bumps {len(u)}")

    charts = os.path.join(d, "charts")
    os.makedirs(charts, exist_ok=True)
    for i, g in enumerate(groups, 1):
        c = g["taps"][0]
        svg_chart([r for r in rows if c - 7000 <= r[0] <= c + 3000], c, f"mark {i} ({g['kind']})", os.path.join(charts, f"mark_{i}.svg"))
    for i, b in enumerate(bumps, 1):
        c = b["peak_t"]
        svg_chart([r for r in rows if c - 7000 <= r[0] <= c + 3000], c, f"bump {i} ({b['kind']})", os.path.join(charts, f"bump_{i}.svg"))
    print(f"\nCharts: {os.path.abspath(charts)}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "analyse"
    if cmd == "pull":
        pull()
    elif cmd == "analyse":
        analyse(sys.argv[2] if len(sys.argv) > 2 else None)
    else:
        sys.exit(__doc__)
