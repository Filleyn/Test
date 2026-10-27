"""Download the PAD-US (USGS Protected Areas Database) file for one state.

PAD-US is published on ScienceBase as one zipped geodatabase per state. This
finds the state's file through the ScienceBase JSON API and downloads it.
"""
import argparse
import json
import sys
import urllib.request

# PAD-US 4.1, by state.
ITEM = "6759abcfd34edfeb8710a004"


def files(item):
    url = f"https://www.sciencebase.gov/catalog/item/{item}?format=json&fields=files,title"
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r).get("files", [])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--state", required=True, help="two-letter code, e.g. OR")
    ap.add_argument("--out", required=True)
    ap.add_argument("--item", default=ITEM)
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    fs = files(a.item)
    if a.list:
        for f in fs:
            print(f.get("name"), f.get("size"), f.get("url") or f.get("downloadUri"))
        return
    st = a.state.upper()
    match = [f for f in fs if f"_{st}." in f.get("name", "") or f"_{st}_" in f.get("name", "")
             or f.get("name", "").upper().endswith(f"{st}.ZIP")]
    if not match:
        print("No file for", st, "in", [f.get("name") for f in fs], file=sys.stderr)
        sys.exit(1)
    f = match[0]
    url = f.get("url") or f.get("downloadUri")
    print("Downloading", f["name"], f.get("size"), url)
    urllib.request.urlretrieve(url, a.out)


if __name__ == "__main__":
    main()
