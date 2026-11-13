"""Toca num botão do app pelo nome de acessibilidade (content-desc) ou texto."""
import re
import subprocess
import sys

alvo = sys.argv[1]
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], check=False, capture_output=True)
xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout

for node in re.findall(r"<node [^>]*>", xml):
    desc = re.search(r'content-desc="([^"]*)"', node)
    text = re.search(r' text="([^"]*)"', node)
    if (desc and desc.group(1) == alvo) or (text and text.group(1) == alvo):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            x, y = (x1 + x2) // 2, (y1 + y2) // 2
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
            print(f"tocou em '{alvo}' ({x},{y})")
            sys.exit(0)
print(f"não encontrou '{alvo}' na tela")
