import os
from PIL import Image, ImageDraw, ImageFilter

def generate_icons(src_path, res_root):
    im = Image.open(src_path)
    # Squircle bbox in 2048x2048 source: (501, 502, 1547, 1547)
    sq_raw = im.crop((501, 502, 1547, 1547))

    densities = [
        ('mdpi', 108, 48),
        ('hdpi', 162, 72),
        ('xhdpi', 216, 96),
        ('xxhdpi', 324, 144),
        ('xxxhdpi', 432, 192),
    ]

    # Pre-render 432x432 master assets
    master_adaptive_size = 432
    master_legacy_size = 192

    # 1. Master Background (Dark radial vignette)
    bg_master = Image.new('RGBA', (master_adaptive_size, master_adaptive_size), (32, 31, 36, 255))
    overlay = Image.new('RGBA', (master_adaptive_size, master_adaptive_size), (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    d.ellipse((36, 36, 396, 396), fill=(70, 68, 76, 255))
    overlay = overlay.filter(ImageFilter.GaussianBlur(50))
    bg_master = Image.alpha_composite(bg_master, overlay)

    # 2. Master Foreground (Centered symbol with feathered shadow)
    sq_360 = sq_raw.resize((360, 360), Image.Resampling.LANCZOS)
    fg_master = Image.new('RGBA', (master_adaptive_size, master_adaptive_size), (0, 0, 0, 0))
    fg_mask = Image.new('L', (360, 360), 0)
    d_mask = ImageDraw.Draw(fg_mask)
    d_mask.rectangle([(40, 40), (320, 320)], fill=255)
    fg_mask = fg_mask.filter(ImageFilter.GaussianBlur(30))
    fg_master.paste(sq_360, (36, 36), fg_mask)

    # 3. Master Monochrome (Material You / Themed Icon)
    def render_monochrome(size):
        scale = size / 432.0
        img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        WHITE = (255, 255, 255, 255)

        def s_pt(x, y):
            return (x * scale, y * scale)

        def slanted(cx_top, y_top, cx_bot, y_bot, w):
            dx = w / 2
            return [s_pt(cx_top - dx, y_top), s_pt(cx_top + dx, y_top),
                    s_pt(cx_bot + dx, y_bot), s_pt(cx_bot - dx, y_bot)]

        # Slanted pillars (w=38)
        draw.polygon(slanted(188, 98, 160, 335, 38), fill=WHITE)
        draw.polygon(slanted(275, 98, 247, 335, 38), fill=WHITE)

        # Vertical shelf side posts
        draw.rectangle([s_pt(100, 98), s_pt(110, 335)], fill=WHITE)
        draw.rectangle([s_pt(324, 98), s_pt(334, 335)], fill=WHITE)

        # Horizontal shelves
        draw.rectangle([s_pt(100, 156), s_pt(334, 168)], fill=WHITE)
        draw.rectangle([s_pt(100, 247), s_pt(334, 259)], fill=WHITE)

        # Top shelf books & items
        draw.rectangle([s_pt(123, 116), s_pt(132, 156)], fill=WHITE)
        draw.rectangle([s_pt(134, 122), s_pt(142, 156)], fill=WHITE)
        draw.polygon([s_pt(144, 126), s_pt(152, 129), s_pt(159, 156), s_pt(151, 156)], fill=WHITE)
        draw.rectangle([s_pt(212, 120), s_pt(222, 156)], fill=WHITE)
        draw.rectangle([s_pt(230, 137), s_pt(248, 156)], fill=WHITE)
        draw.ellipse([s_pt(302, 132), s_pt(323, 153)], fill=WHITE)

        # Bottom shelf items
        draw.pieslice([s_pt(122, 226), s_pt(144, 248)], 180, 360, fill=WHITE)
        draw.ellipse([s_pt(212, 224), s_pt(224, 236)], fill=WHITE)
        draw.rectangle([s_pt(202, 236), s_pt(234, 247)], fill=WHITE)
        draw.rectangle([s_pt(288, 208), s_pt(297, 247)], fill=WHITE)
        draw.rectangle([s_pt(299, 212), s_pt(308, 247)], fill=WHITE)
        draw.polygon([s_pt(310, 218), s_pt(318, 221), s_pt(325, 247), s_pt(317, 247)], fill=WHITE)

        return img

    mono_master = render_monochrome(master_adaptive_size)

    # 4. Master Legacy Icons (Square squircle & Round)
    legacy_master = sq_raw.resize((master_legacy_size, master_legacy_size), Image.Resampling.LANCZOS)
    
    # Circular master legacy
    round_mask = Image.new('L', (master_legacy_size, master_legacy_size), 0)
    d_round = ImageDraw.Draw(round_mask)
    d_round.ellipse((4, 4, master_legacy_size - 4, master_legacy_size - 4), fill=255)
    legacy_round_master = Image.new('RGBA', (master_legacy_size, master_legacy_size), (0, 0, 0, 0))
    legacy_round_master.paste(legacy_master, (0, 0), round_mask)

    for density, adapt_size, legacy_size in densities:
        out_dir = os.path.join(res_root, f'mipmap-{density}')
        os.makedirs(out_dir, exist_ok=True)

        # Background
        bg_scaled = bg_master.resize((adapt_size, adapt_size), Image.Resampling.LANCZOS)
        bg_scaled.save(os.path.join(out_dir, 'ic_launcher_background.png'))

        # Foreground
        fg_scaled = fg_master.resize((adapt_size, adapt_size), Image.Resampling.LANCZOS)
        fg_scaled.save(os.path.join(out_dir, 'ic_launcher_foreground.png'))

        # Monochrome
        mono_scaled = render_monochrome(adapt_size)
        mono_scaled.save(os.path.join(out_dir, 'ic_launcher_monochrome.png'))

        # Legacy Square
        leg_sq = legacy_master.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        leg_sq.save(os.path.join(out_dir, 'ic_launcher.png'))

        # Legacy Round
        leg_rd = legacy_round_master.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        leg_rd.save(os.path.join(out_dir, 'ic_launcher_round.png'))

        print(f'Generated {density} ({adapt_size}x{adapt_size} adaptive, {legacy_size}x{legacy_size} legacy)')

    # Create mipmap-anydpi-v26 XMLs
    v26_dir = os.path.join(res_root, 'mipmap-anydpi-v26')
    os.makedirs(v26_dir, exist_ok=True)

    xml_content = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
'''
    with open(os.path.join(v26_dir, 'ic_launcher.xml'), 'w', encoding='utf-8') as f:
        f.write(xml_content)

    with open(os.path.join(v26_dir, 'ic_launcher_round.xml'), 'w', encoding='utf-8') as f:
        f.write(xml_content)

    print('Generated mipmap-anydpi-v26 XML definitions')

if __name__ == '__main__':
    generate_icons('local_share/icon.png', 'app/src/main/res')
