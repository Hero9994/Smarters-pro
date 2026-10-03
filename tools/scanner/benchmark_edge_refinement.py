"""Paired desktop experiments on the recorded Android model polygons.

This is a diagnostic, not a replacement for instrumented Android acceptance.
The Java PRNG, sampling, 85% support gate, 6-source-pixel margin and blur guard
match production. Desktop JPEG decoding can still differ. Ground truth is used
ONLY after predictions, never for selecting image candidates or crop settings.
"""
import argparse
import json
import math
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import time

import cv2
import numpy as np
from analyze_edges import area, intersections, line, metrics, valid

cv2.setNumThreads(1)


class JavaRandom:
    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.seed >> (48 - bits)

    def next_int(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            value = bits % bound
            if bits - value + bound - 1 < (1 << 31):
                return value


def fit(points, profiles, strengths, widths, reference, midpoint, radius, minimum_support=.85):
    if len(points) < 40:
        return None
    rng = JavaRandom(73071446)
    best = None
    score = -1
    for _ in range(192):
        a, b = points[rng.next_int(len(points))], points[rng.next_int(len(points))]
        if np.linalg.norm(a - b) < 40:
            continue
        ll = line(a, b)
        if abs(ll[:2] @ reference[:2]) < math.cos(math.radians(7.5)):
            continue
        residual = np.abs(points @ ll[:2] + ll[2])
        candidates = np.nonzero(residual <= 2.5)[0]
        ordered = candidates[np.lexsort((candidates, residual[candidates], profiles[candidates]))]
        choices = ordered[np.unique(profiles[ordered], return_index=True)[1]]
        if len(choices) < 40:
            continue
        value = len(choices) * math.exp(-.5 * ((abs(ll[:2] @ midpoint + ll[2])) / (radius * .65)) ** 2)
        if value > score:
            score, best = value, choices
    if best is None or len(best) < math.ceil(96 * minimum_support):
        return None
    pts = points[best]
    weights = np.clip(strengths[best] / 70, .4, 1.5)
    center = np.average(pts, axis=0, weights=weights)
    cov = ((pts - center) * weights[:, None]).T @ (pts - center)
    xx, xy, yy = cov[0, 0], cov[0, 1], cov[1, 1]
    angle = .5 * math.atan2(2 * xy, xx - yy)
    n = np.array([-math.sin(angle), math.cos(angle)])
    ll = np.r_[n, -n @ center]
    residual = float(np.sqrt(np.mean((pts @ n + ll[2]) ** 2)))
    if residual > 2.7 or abs(n @ reference[:2]) < math.cos(math.radians(7.5)) or abs(ll[:2] @ midpoint + ll[2]) > radius * 1.15:
        return None
    return ll, residual, len(best) / 96, float(np.median(widths[best]))


def refine(image, initial, variant):
    if variant == 'color_fallback':
        ordinary = refine(image, initial, 'partial_strong')
        if ordinary[2] == 4:
            return ordinary
        colored = refine(image, initial, 'color_combined')
        if colored[2] != 4:
            return ordinary
        # This is an experimental editor suggestion, always reviewed. Do not
        # change acceptance thresholds or disguise uncertainty with padding.
        return (*colored[:-1], True)
    h, w = image.shape[:2]
    gray = cv2.GaussianBlur(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), (3, 3), .6)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    if variant == 'color_combined':
        color = cv2.GaussianBlur(image.astype(np.float32), (3, 3), .6)
        cx = cv2.Sobel(color, cv2.CV_32F, 1, 0, ksize=3)
        cy = cv2.Sobel(color, cv2.CV_32F, 0, 1, ksize=3)
    radius = float(np.clip(min(w, h) * .027, 20, 180))
    r = round(radius)
    lines, residuals, supports, ridge_widths = [], [], [], []
    accepted = 0
    for edge in range(4):
        a, b = initial[edge], initial[(edge + 1) % 4]
        ref = line(a, b)
        n = ref[:2]
        positions = a + (b - a) * np.linspace(.025, .975, 96)[:, None]
        points, groups, strengths, widths = [], [], [], []
        for group, p in enumerate(positions):
            ds = np.arange(-r - 32, r + 33)
            coords = np.floor(p + ds[:, None] * n + .5).astype(int)
            ok = (coords[:, 0] >= 1) & (coords[:, 0] < w - 1) & (coords[:, 1] >= 1) & (coords[:, 1] < h - 1)
            clipped = np.clip(coords, [0, 0], [w - 1, h - 1])
            response = np.abs(gx[clipped[:, 1], clipped[:, 0]] * n[0] + gy[clipped[:, 1], clipped[:, 0]] * n[1])
            if variant == 'color_combined':
                response = np.linalg.norm(cx[clipped[:, 1], clipped[:, 0]] * n[0] + cy[clipped[:, 1], clipped[:, 0]] * n[1], axis=1) / math.sqrt(3)
            response[~ok] = 0
            lum = gray[clipped[:, 1], clipped[:, 0]].astype(float)
            base = r + 32
            peaks = np.nonzero((response[1:-1] >= response[:-2]) & (response[1:-1] >= response[2:]))[0] + 1
            peaks = peaks[(ds[peaks] >= -r + 6) & (ds[peaks] < r - 6) & ok[peaks]]
            candidates = []
            for k in peaks:
                if response[k] < (12 if variant == 'weak_guarded' else 24) or not ok[k - 5] or not ok[k + 5]:
                    continue
                step = abs(lum[k - 5] - lum[k + 5])
                if variant == 'color_combined':
                    step = float(np.linalg.norm(color[clipped[k - 5, 1], clipped[k - 5, 0]] - color[clipped[k + 5, 1], clipped[k + 5, 0]]) / math.sqrt(3))
                if step < (3 if variant == 'weak_guarded' else 4):
                    continue
                # Persistent two-sided contrast distinguishes a physical material
                # transition from a thin printed stroke, without assuming white paper.
                signed = lum[k + 5] - lum[k - 5]
                near = np.median(lum[k + 7:k + 16]) - np.median(lum[k - 15:k - 6])
                far = np.median(lum[k + 16:k + 25]) - np.median(lum[k - 24:k - 15])
                persistence = max(0, min(abs(near), abs(far))) if near * signed > 0 and far * signed > 0 else 0
                if variant == 'persistent':
                    step = step * .35 + persistence * .65
                elif variant == 'persistent_gate':
                    if persistence < 4:
                        continue
                    step = step * .5 + persistence * .5
                strength = math.sqrt(response[k] * step)
                candidates.append((k, strength, strength * math.exp(-.5 * (ds[k] / (radius * .65)) ** 2)))
            selected = []
            for k, strength, _ in sorted(candidates, key=lambda item: -item[2]):
                if any(abs(ds[k] - ds[j]) < 3 for j in selected):
                    continue
                selected.append(k)
                denominator = response[k - 1] - 2 * response[k] + response[k + 1]
                delta = float(np.clip(.5 * (response[k - 1] - response[k + 1]) / denominator, -.75, .75)) if abs(denominator) > 1e-6 else 0
                left = right = 0
                while left < 24 and response[k - left - 1] >= response[k] * .5:
                    left += 1
                while right < 24 and response[k + right + 1] >= response[k] * .5:
                    right += 1
                points.append(p + n * (ds[k] + delta))
                groups.append(group)
                strengths.append(strength)
                widths.append(left + right + 1)
                if len(selected) == 6:
                    break
        fitted = fit(np.array(points), np.array(groups), np.array(strengths), np.array(widths), ref, (a + b) / 2, radius, .65 if variant == 'weak_guarded' else .85)
        if fitted:
            lines.append(fitted[0])
            residuals.append(fitted[1])
            supports.append(fitted[2])
            ridge_widths.append(fitted[3])
            accepted += 1
        else:
            lines.append(ref)
            residuals.append(-1)
            supports.append(0)
            ridge_widths.append(-1)
    chosen = intersections(lines)
    plausible = valid(chosen / [w - 1, h - 1]) and .75 <= area(chosen) / area(initial) <= 1.3 and max(np.linalg.norm((chosen - initial) / [w - 1, h - 1], axis=1)) < .1
    strong_partial = accepted == 3 and len([s for s in supports if s > 0]) == 3 and all(s >= .95 for s in supports if s > 0) and all(width <= 6 for width in ridge_widths if width > 0)
    use_partial = variant == 'partial_three' or (variant == 'partial_strong' and strong_partial)
    if not plausible or accepted < (3 if use_partial else 4):
        chosen = initial.copy()
    padding = 6
    center = chosen.mean(axis=0)
    lines = [line(chosen[e], chosen[(e + 1) % 4]) for e in range(4)]
    for ll in lines:
        ll[2] += padding * (1 if ll[:2] @ center + ll[2] > 0 else -1)
    padded = np.clip(intersections(lines), [0, 0], [w - 1, h - 1])
    manual = variant == 'weak_guarded' or accepted < 4 or not plausible or any(width > padding for width in ridge_widths)
    return chosen, padded, accepted, residuals, supports, ridge_widths, manual


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--records', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--limit', type=int, default=0)
    parser.add_argument('--variants', nargs='+', default=['baseline', 'persistent', 'persistent_gate'])
    args = parser.parse_args()
    records = json.loads(args.records.read_text())
    if args.limit:
        records = records[:args.limit]
    variants = args.variants

    def process(record):
        frame = 'frame_' + record['file'].split('_frame_')[1]
        image = cv2.imread(str(args.dataset / record['sequence'] / frame))
        assert image is not None
        initial, gt = np.array(record['model_polygon']), np.array(record['ground_truth_polygon'])
        out = {'file': record['file'], 'sequence': record['sequence']}
        for variant in variants:
            start = time.perf_counter()
            before, after, count, res, support, widths, manual = refine(image, initial, variant)
            out[variant] = {**metrics(after, gt), 'boundary': metrics(before, gt), 'accepted': count, 'manual': manual or record['model_confidence'] < .72,
                'residual': res, 'support': support, 'widths': widths, 'polygon': after.tolist(), 'boundary_polygon': before.tolist(), 'ms': (time.perf_counter() - start) * 1000}
        return out

    rows = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        for i, row in enumerate(pool.map(process, records)):
            rows.append(row)
            if i % 30 == 0:
                print('Processed', i, flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rows, indent=2))
    for variant in variants:
        rs = [r[variant] for r in rows]
        print(variant, {'n': len(rs), 'boundary_edge_mean': float(np.mean([r['boundary']['edge'] for r in rs])),
            'boundary_corner_mean': float(np.mean([r['boundary']['corner'] for r in rs])), 'crop_inward_gt2': sum(r['inward'] > 2 for r in rs),
            'manual': sum(r['manual'] for r in rs), 'unsafe_confident': sum(not r['manual'] and r['inward'] > 2 for r in rs)}, flush=True)


if __name__ == '__main__':
    main()
