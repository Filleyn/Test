#!/usr/bin/env python3
"""Reference reader and router for routing.bin, mirroring the app's Kotlin code.

Used by the build's check report and by the pipeline tests: it proves the file
is laid out as documented and that a route can be found on it.
"""
import gzip
import heapq
import math
import struct

import numpy as np

from routing_bin import HEADER_SIZE, MAGIC, SECTIONS

R = 6371008.8


def load(path):
    with open(path, "rb") as f:
        raw = f.read()
    if path.endswith(".gz"):
        raw = gzip.decompress(raw)
    if raw[:4] != MAGIC:
        raise ValueError("not a TrailGuide routing file")
    version, nv, ne, nc, rows, cols, nnames = struct.unpack_from("<7I", raw, 4)
    cell, min_lat, min_lon = struct.unpack_from("<3d", raw, 32)
    offs = struct.unpack_from(f"<{len(SECTIONS)}Q", raw, 56)
    off = dict(zip(SECTIONS, offs))

    def arr(name, dtype, count):
        return np.frombuffer(raw, dtype=dtype, count=count, offset=off[name])

    g = {
        "nv": nv, "ne": ne, "rows": rows, "cols": cols, "cell": cell, "min_lat": min_lat, "min_lon": min_lon,
        "vlat": arr("vlat", "<i4", nv) / 1e6, "vlon": arr("vlon", "<i4", nv) / 1e6,
        "adj_start": arr("adj_start", "<i4", nv + 1), "adj_edges": arr("adj_edges", "<i4", 2 * ne),
        "e_from": arr("e_from", "<i4", ne), "e_to": arr("e_to", "<i4", ne), "e_len": arr("e_len", "<f4", ne),
        "e_gain": arr("e_gain", "<f4", ne), "e_loss": arr("e_loss", "<f4", ne), "e_name": arr("e_name", "<i4", ne),
        "geom_start": arr("geom_start", "<i4", ne + 1),
        "c_lat": arr("c_lat", "<i4", nc) / 1e6, "c_lon": arr("c_lon", "<i4", nc) / 1e6,
        "cell_start": arr("cell_start", "<i4", rows * cols + 1),
    }
    g["cell_edges"] = arr("cell_edges", "<i4", int(g["cell_start"][-1]))
    names, p = [], off["names"] + 4
    for _ in range(nnames):
        (n,) = struct.unpack_from("<H", raw, p)
        names.append(raw[p + 2:p + 2 + n].decode("utf-8"))
        p += 2 + n
    g["names"] = names
    return g


def dist_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))


def edge_coords(g, e):
    s, t = g["geom_start"][e], g["geom_start"][e + 1]
    return list(zip(g["c_lat"][s:t], g["c_lon"][s:t]))


def snap(g, lat, lon, max_m=400.0):
    """Nearest point on any edge: (edge, fraction along it, lat, lon, distance m)."""
    r0 = int((lat - g["min_lat"]) / g["cell"])
    c0 = int((lon - g["min_lon"]) / g["cell"])
    seen, best = set(), None
    kx = math.cos(math.radians(lat)) * 111320.0
    ky = 110540.0
    for r in range(r0 - 1, r0 + 2):
        for c in range(c0 - 1, c0 + 2):
            if not (0 <= r < g["rows"] and 0 <= c < g["cols"]):
                continue
            k = r * g["cols"] + c
            for e in g["cell_edges"][g["cell_start"][k]:g["cell_start"][k + 1]]:
                e = int(e)
                if e in seen:
                    continue
                seen.add(e)
                pts = edge_coords(g, e)
                along, total = 0.0, 0.0
                seglens = []
                for (a_lat, a_lon), (b_lat, b_lon) in zip(pts, pts[1:]):
                    seglens.append(math.hypot((b_lon - a_lon) * kx, (b_lat - a_lat) * ky))
                total = sum(seglens) or 1e-9
                for i, ((a_lat, a_lon), (b_lat, b_lon)) in enumerate(zip(pts, pts[1:])):
                    ax, ay = (a_lon - lon) * kx, (a_lat - lat) * ky
                    dx, dy = (b_lon - a_lon) * kx, (b_lat - a_lat) * ky
                    l2 = dx * dx + dy * dy
                    t = 0.0 if l2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
                    px, py = ax + t * dx, ay + t * dy
                    d = math.hypot(px, py)
                    if best is None or d < best[4]:
                        f = (along + t * seglens[i]) / total
                        best = (e, f, a_lat + t * (b_lat - a_lat), a_lon + t * (b_lon - a_lon), d)
                    along += seglens[i]
    if best is None or best[4] > max_m:
        return None
    return best


def route(g, a, b, max_settled=3_000_000):
    """A* between two snaps. Returns (length m, list of (edge, forward)) or None."""
    if a[0] == b[0]:
        same = abs(a[1] - b[1]) * float(g["e_len"][a[0]])
    else:
        same = math.inf
    tlat, tlon = b[2], b[3]
    INF = math.inf
    gcost = {}
    prev = {}
    heap = []

    def h(v):
        return dist_m(g["vlat"][v], g["vlon"][v], tlat, tlon)

    ea = a[0]
    la = float(g["e_len"][ea])
    for v, cost in ((int(g["e_from"][ea]), a[1] * la), (int(g["e_to"][ea]), (1 - a[1]) * la)):
        if cost < gcost.get(v, INF):
            gcost[v] = cost
            prev[v] = -1
            heapq.heappush(heap, (cost + h(v), cost, v))
    eb = b[0]
    lb = float(g["e_len"][eb])
    b_from, b_to = int(g["e_from"][eb]), int(g["e_to"][eb])
    best, best_v = same, None
    settled = 0
    closed = set()
    while heap:
        f, cost, v = heapq.heappop(heap)
        if f >= best:
            break
        if v in closed:
            continue
        closed.add(v)
        settled += 1
        if settled > max_settled:
            return None
        if v == b_from and cost + b[1] * lb < best:
            best, best_v = cost + b[1] * lb, v
        if v == b_to and cost + (1 - b[1]) * lb < best:
            best, best_v = cost + (1 - b[1]) * lb, v
        for i in range(g["adj_start"][v], g["adj_start"][v + 1]):
            e = int(g["adj_edges"][i])
            u = int(g["e_to"][e]) if int(g["e_from"][e]) == v else int(g["e_from"][e])
            nc = cost + float(g["e_len"][e])
            if nc < gcost.get(u, INF):
                gcost[u] = nc
                prev[u] = e
                heapq.heappush(heap, (nc + h(u), nc, u))
    if best == INF:
        return None
    path = []
    v = best_v
    while v is not None and prev.get(v, -1) != -1:
        e = prev[v]
        fwd = int(g["e_to"][e]) == v
        path.append((e, fwd))
        v = int(g["e_from"][e]) if fwd else int(g["e_to"][e])
    path.reverse()
    return best, path
