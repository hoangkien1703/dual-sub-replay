from pathlib import Path

path = Path("app/src/main/java/com/kienhoang/dualsubreplay/ui/LearningPlayerRoot.kt")
text = path.read_text()
old = "currentHorizontalPosition + delta / horizontalDragTravelPx * 2f"
new = "currentHorizontalPosition + delta / horizontalDragTravelPx"
if text.count(old) != 1:
    raise SystemExit(f"Expected one horizontal drag formula, found {text.count(old)}")
path.write_text(text.replace(old, new, 1))
