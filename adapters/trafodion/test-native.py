#!/usr/bin/env python3
"""Real native regression cases; a protocol fixture cannot satisfy these checks."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile

home = pathlib.Path(sys.argv[1]).resolve()
manifest = json.loads((home / "provenance.json").read_text())
root = pathlib.Path(manifest["sourceRoot"])
project = pathlib.Path(__file__).resolve().parents[2]
catalog = json.loads((project / "src/main/resources/trafodion-catalog.json").read_text())
rules = {r["id"]: r["title"] for r in catalog["rules"]}
environment = dict(os.environ, TRAF_HOME=str(root / "core/sqf"))
environment["LD_LIBRARY_PATH"] = ":".join([
    str(root / "core/sql/lib/linux/64bit/debug"), str(root / "core/sqf/export/lib64d"),
    environment.get("LD_LIBRARY_PATH", "")])
schema = [{"name": "customers", "columns": [{"name": "id", "type": "INTEGER", "nullable": False}]},
          {"name": "orders", "columns": [{"name": "customer_id", "type": "INTEGER"}, {"name": "total", "type": "INTEGER"}]}]

def run(sql, selected=(), extra=None, expected_error=None):
    request = dict(database="trafodion", inputMode="sql", sql=sql, schema=schema,
                   rules=list(selected), options={"matchLimit": 80})
    request.update(extra or {})
    with tempfile.TemporaryDirectory(prefix="optilens-native-test-") as work:
        work = pathlib.Path(work)
        payload = {"request": request, "observedRules": [{"id": r, "name": rules[r]} for r in selected]}
        (work / "input.json").write_text(json.dumps(payload))
        env = dict(environment, TRAF_LITE_STORE_DIR=str(work / "store"))
        process = subprocess.run([manifest["binary"], str(work / "input.json"), str(work / "output.json")],
                                 cwd=work, env=env, capture_output=True, timeout=25)
        assert process.returncode == 0, process.stderr.decode(errors="replace")[-2000:]
        result = json.loads((work / "output.json").read_text())
        if expected_error:
            assert expected_error in result.get("error", ""), result
        else:
            assert "error" not in result, result
            assert result["sourceCommit"] == manifest["sourceCommit"]
            assert result["initial"]["nodes"] and result["finalPlan"]["nodes"]
            assert len(result["events"]) <= 80
            assert all(e["rule"] in selected for e in result["events"])
        return result

join = "SELECT c.id, o.total FROM customers c JOIN orders o ON c.id = o.customer_id WHERE o.total > 100"
observed = run(join, ["Trafodion.HashJoinRule", "Trafodion.JoinCommutativityRule"])
assert observed["events"], "The native join query produced no selected-rule candidates"
assert any(e["before"] != e["after"] for e in observed["events"])
for target in ["Trafodion.HashJoinRule", "Trafodion.JoinCommutativityRule"]:
    isolated = run(join, [target])
    assert any(e["rule"] == target and e["before"] != e["after"] for e in isolated["events"]), target
plain = run(join)
assert not plain["events"]
assert plain["finalPlan"] == observed["finalPlan"], "Observing rules changed the selected native plan"
run("SELECT customer_id, COUNT(*) FROM orders GROUP BY customer_id")
run("VALUES (1 + 2)")
run("SELECT * FROM missing_request_table", expected_error="failed")
run("DELETE FROM customers", expected_error="read-only")
run("CREATE TABLE x (id INTEGER)", expected_error="read-only")
run("SELECT id FROM customers", extra={"database": "calcite"}, expected_error="Wrong native engine")
run("SELECT id FROM customers", extra={"inputMode": "plan"}, expected_error="plan")
run("SELECT id FROM customers", extra={"options": {"matchLimit": 0}}, expected_error="Invalid match limit")
all_types = ["INTEGER", "BIGINT", "DOUBLE", "DECIMAL", "VARCHAR", "BOOLEAN", "DATE", "TIMESTAMP"]
run("SELECT * FROM typed_table", extra={"schema": [{"name": "typed_table", "columns": [
    {"name": "c" + str(i), "type": t, "nullable": bool(i % 2)} for i, t in enumerate(all_types)]}]})
print("13 real native SQL/schema/error/isolation cases passed.")
print("Observed changed native candidates:", {r: sum(e["rule"] == r for e in observed["events"]) for r in set(e["rule"] for e in observed["events"])})
