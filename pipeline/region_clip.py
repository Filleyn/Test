"""Helpers for keeping only what's inside the state's outline (see region_poly.py)."""
import json

import mercantile
from shapely.geometry import box, shape
from shapely.prepared import prep


def load(path):
    if not path:
        return None
    g = json.load(open(path))
    return shape(g["geometry"] if g.get("type") == "Feature" else g)


def tile_keeper(path):
    """A test for (z, x, y): True if the tile touches the outline (or there's no outline)."""
    poly = load(path)
    if poly is None:
        return lambda z, x, y: True
    p = prep(poly)

    def keep(z, x, y):
        b = mercantile.bounds(x, y, z)
        return p.intersects(box(b.west, b.south, b.east, b.north))
    return keep


def point_keeper(path):
    """A test for a GeoJSON geometry: True if its representative point is inside the outline."""
    poly = load(path)
    if poly is None:
        return lambda geom: True
    p = prep(poly)

    def keep(geom):
        try:
            return p.contains(shape(geom).representative_point())
        except Exception:  # noqa: BLE001
            return True
    return keep
