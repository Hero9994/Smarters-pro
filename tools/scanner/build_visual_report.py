"""Render recorded Android geometry on public benchmark images without re-detection.

Uses source coordinates from records.json, preserves the original image, and
labels the explicit padded crop separately from the fitted paper boundary.
Public SmartDoc material must retain CC-BY-4.0 attribution. Never use private
user captures in a source-controlled report.
"""
import argparse
import html
import json
import shutil
from pathlib import Path
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser()
parser.add_argument('--dataset', type=Path, required=True)
parser.add_argument('--records', type=Path, required=True)
parser.add_argument('--android-output', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
records = json.loads(args.records.read_text())
args.output.mkdir(parents=True, exist_ok=True)
indices = [60, 124, 200, 270, 280, 299]
rows = []
for index in indices:
    row = records[index]
    frame = 'frame_' + row['file'].split('_frame_')[1]
    source = args.dataset / row['sequence'] / frame
    original_name = f'{index:03}-source.jpeg'
    overlay_name = f'{index:03}-geometry.png'
    shutil.copyfile(source, args.output / original_name)
    with Image.open(source) as original:
        image = original.convert('RGB')
    scale = min(1.0, 1200 / max(image.size))
    image = image.resize((round(image.width * scale), round(image.height * scale)))
    draw = ImageDraw.Draw(image)
    for key, color in [('ground_truth_polygon', '#ff3b30'), ('model_polygon', '#ffff00'),
                       ('boundary_polygon', '#00d9ff'), ('refined_polygon', '#00ff66')]:
        points = [(p[0] * scale, p[1] * scale) for p in row[key]]
        draw.line(points + [points[0]], fill=color, width=2)
    image.save(args.output / overlay_name)
    perspective = args.android_output / f'{index:03}-perspective.png'
    perspective_name = f'{index:03}-android-perspective.png'
    if perspective.is_file():
        shutil.copyfile(perspective, args.output / perspective_name)
    else:
        perspective_name = None
    summary = {key: row[key] for key in ['file', 'manual_review', 'accepted_edges',
        'boundary_corner_error_px', 'boundary_edge_error_px', 'corner_error_px',
        'edge_error_px', 'max_inward_px', 'crop_padding_source_px']}
    if 'edge_transition_width_px' in row:
        summary['edge_transition_width_px'] = row['edge_transition_width_px']
    (args.output / f'{index:03}-measurement.json').write_text(json.dumps(summary, indent=2) + '\n')
    text = (f"Paper-edge error {row['boundary_edge_error_px']:.2f} px; inward {row['max_inward_px']:.2f} px; "
            f"manual review {'required' if row['manual_review'] else 'not flagged'}. Margin {row['crop_padding_source_px']:.0f} source px.")
    pictures = f'<a href="{original_name}"><img src="{original_name}" alt="Original public benchmark frame"></a>'
    pictures += f'<a href="{overlay_name}"><img src="{overlay_name}" alt="Recorded Android polygons"></a>'
    if perspective_name:
        pictures += f'<a href="{perspective_name}"><img src="{perspective_name}" alt="Actual Android perspective output"></a>'
    rows.append(f'<article><h2>{index:03}: {html.escape(row["file"])}</h2><p>{html.escape(text)}</p>'
                f'<div class="images">{pictures}</div><a href="{index:03}-measurement.json">Exact measurements</a></article>')
page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Recorded Android scanner geometry</title><style>
body{font:16px system-ui;margin:24px auto;max-width:1400px;padding:0 18px;color:#17212b;background:#f3f5f7}
article{padding:18px;margin:20px 0;background:white;border:1px solid #d5dce1;border-radius:10px}
h2{font-size:18px;overflow-wrap:anywhere}.images{display:flex;gap:12px;align-items:flex-start;flex-wrap:wrap}
.images a{flex:1;min-width:230px;max-width:600px}.images img{width:100%;display:block;border:1px solid #d5dce1}
.legend span{padding:4px 8px;border-radius:5px;background:#17212b;font-weight:bold;display:inline-block;margin:4px}
</style><h1>Scanner geometry evidence</h1>
<p>Original public frames, polygons recorded by the Android benchmark, and actual Android perspective outputs when saved.
The overlay is a drawing of recorded coordinates; it does not run another detector or modify document content.
All numbers are source pixels, not physical millimetres. Manual fallback is a limitation, not a success declaration.</p>
<p class="legend"><span style="color:#ff3b30">Red: publisher ground truth</span><span style="color:#ffff00">Yellow: AI estimate</span>
<span style="color:#00d9ff">Cyan: raw fitted boundary</span><span style="color:#00ff66">Green: final crop with explicit margin</span></p>
'''+ '\n'.join(rows) + '''<footer><p>SmartDoc 2015 challenge 1 v2.0.0, CC-BY-4.0.
Burie, Chazalon, Coustaty, Eskenazi, Luqman, Mehri, Nayef, Ogier, Un and Rusinol; ICDAR 2015 SmartDoc competition.
<a href="https://github.com/jchazalon/smartdoc15-ch1-dataset">Publisher and dataset license</a>.
300 frames cover 30 independent documents. Training overlap is unknown. Private user photographs are excluded.</p></footer></html>'''
(args.output / 'index.html').write_text(page)
print(f'Rendered {len(rows)} public benchmark cases with recorded Android geometry')
