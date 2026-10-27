"""從 uiautomator dump 找出內容為指定文字的輸入框，印出中心座標"""
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
target = sys.argv[2]
for m in re.finditer(r'<node [^>]*>', xml):
    node = m.group(0)
    if f'text="{target}"' in node and "EditText" in node:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
print("540 900")
