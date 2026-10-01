"""Synthetic 50 MP regression fixture; no personal or downloaded document data.
Pillow and the system DejaVu font are build-host tools, not APK dependencies.
The committed JPEG is test-only and never packaged into the main application.
"""
from pathlib import Path
import hashlib, json
from PIL import Image, ImageDraw, ImageFont

target = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/scanner-fixtures/large-50mp.jpg"
target.parent.mkdir(parents=True, exist_ok=True)
image = Image.new("RGB", (10_000, 5_000), (237, 237, 232))
draw = ImageDraw.Draw(image)
draw.rectangle((0, 0, 3_500, 5_000), fill=(192, 192, 187))
draw.rectangle((100, 100, 650, 650), fill=(180, 20, 20))
draw.rectangle((9_000, 4_300, 9_900, 4_900), fill=(20, 40, 180))
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 144)
draw.text((900, 900), "OFFICIAL DOCUMENT 73071446", font=font, fill="black")
draw.text((900, 1_400), "Reference 16010195K535", font=font, fill="black")
draw.text((900, 1_900), "Payment 15.07.2026 24.90 EUR", font=font, fill="black")
draw.line((700, 3_800, 7_000, 3_850), fill=(20, 40, 180), width=30)
image.save(target, "JPEG", quality=96, subsampling=0, progressive=False, optimize=False, dpi=(200, 200))
image.close()
with target.open("rb") as stream: digest = hashlib.file_digest(stream, "sha256").hexdigest()
print(json.dumps({"fixture": str(target), "pixels": 50_000_000, "sha256": digest, "bytes": target.stat().st_size}))
