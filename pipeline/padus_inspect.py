"""Quick look at PAD-US on source.coop for one state, via DuckDB (reads only what it needs)."""
import sys
import duckdb

BASE = "https://data.source.coop/cboettig/padus/padus-4-1"
state = sys.argv[1] if len(sys.argv) > 1 else "OR"
con = duckdb.connect()
con.execute("INSTALL httpfs; LOAD httpfs;")
for layer in ["fee", "designation", "proclamation", "easement", "combined"]:
    url = f"{BASE}/{layer}.parquet"
    try:
        cols = [r[0] for r in con.execute(f"DESCRIBE SELECT * FROM read_parquet('{url}')").fetchall()]
    except Exception as e:  # noqa: BLE001
        print(f"## {layer}: not available ({str(e)[:120]})")
        continue
    print(f"## {layer}: columns {', '.join(cols)}")
    where = f"State_Nm = '{state}'" if "State_Nm" in cols else "TRUE"
    n = con.execute(f"SELECT COUNT(*) FROM read_parquet('{url}') WHERE {where}").fetchone()[0]
    print(f"rows in {state}: {n}")
    for f in ["Category", "Mang_Type", "Mang_Name", "Des_Tp", "Pub_Access"]:
        if f not in cols:
            continue
        rows = con.execute(f"SELECT {f}, COUNT(*) c FROM read_parquet('{url}') WHERE {where} GROUP BY 1 ORDER BY 2 DESC LIMIT 25").fetchall()
        print(f"### {f}: " + "; ".join(f"{a}={b}" for a, b in rows))
    if "Unit_Nm" in cols:
        rows = con.execute(f"SELECT DISTINCT Unit_Nm, Des_Tp, Mang_Name FROM read_parquet('{url}') WHERE {where} AND "
                           "(Unit_Nm ILIKE '%Deschutes%' OR Unit_Nm ILIKE '%Jefferson%' OR Unit_Nm ILIKE '%Willamette National%') LIMIT 20").fetchall()
        print("### examples: " + "; ".join(" / ".join(map(str, r)) for r in rows))
