import sys
from PIL import Image, ImageDraw, ImageFont
ref = Image.open(__import__("os").path.join(__import__("os").path.dirname(__file__), "../../app/src/main/res/drawable-nodpi/approved_design.jpg")).convert("RGB")
imgs = [ref] + [Image.open(p).convert("RGB") for p in sys.argv[2:]]
H = 1280
imgs = [im.resize((round(im.width * H / im.height), H), Image.LANCZOS) for im in imgs]
labels = ["Approved reference"] + [p.split("/")[-2] + "/" + p.split("/")[-1].replace(".png", "") for p in sys.argv[2:]]
gap, top = 24, 44
W = sum(im.width for im in imgs) + gap * (len(imgs) + 1)
out = Image.new("RGB", (W, H + top + gap), (24, 24, 28))
d = ImageDraw.Draw(out)
try: font = ImageFont.truetype(__import__("os").path.expanduser("~/.local/share/fonts/roboto/Roboto_500Medium.ttf"), 22)
except Exception: font = None
x = gap
for im, lab in zip(imgs, labels):
    out.paste(im, (x, top)); d.text((x, 10), lab, fill=(230, 230, 240), font=font); x += im.width + gap
out.save(sys.argv[1])
