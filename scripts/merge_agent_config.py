#!/usr/bin/env python3
"""合併 InstanaAgent configuration_yaml（由 instana-agent-config.sh 呼叫）"""
import json, os, re
cur, add, mark = os.environ["CURRENT"], os.environ["ADDITION"], os.environ["MARK"]
lines = cur.split("\n")
out, i, done = [], 0, False
# 找到未註解的 extra-http-headers: 區塊，在清單最後一項後面插入 X-Patient-Id
while i < len(lines):
    out.append(lines[i])
    m = re.match(r"^(\s*)extra-http-headers:\s*$", lines[i])
    if m and not done:
        j = i + 1
        item_indent = None
        while j < len(lines) and re.match(r"^\s*- ", lines[j]):
            item_indent = re.match(r"^(\s*)- ", lines[j]).group(1)
            out.append(lines[j]); j += 1
        out.append(f"{item_indent or m.group(1) + '  '}- 'X-Patient-Id'   # hospital HIS demo")
        done = True
        i = j
        continue
    i += 1
merged = "\n".join(out)
if not done:
    merged += "\n\ncom.instana.tracing:\n  extra-http-headers:\n    - 'X-Patient-Id'\n"
merged += "\n\n" + mark + "\n" + add + "\n"
print(json.dumps({"spec": {"agent": {"configuration_yaml": merged}}}))
