#!/usr/bin/env python3
"""格式化 /api/chaos 的回應（由 chaos.sh list 呼叫）"""
import json
import sys

d = json.load(sys.stdin)
for k, v in d["scenarios"].items():
    print(f"  {k:18} {v:10} {d['descriptions'][k]}")
print(f"  （洩漏中的連線 {d['leakedConnections']} 條，洩漏記憶體 {d['leakedMemoryMb']} MB）")
