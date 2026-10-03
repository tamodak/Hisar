"""Image embeddings of every labelled picture for each candidate backbone -> emb/<name>.npy (L2-normalised)."""
import json, sys, time, os, numpy as np, torch, open_clip
from PIL import Image
torch.set_num_threads(12)
order = json.load(open('order.json'))
os.makedirs('emb', exist_ok=True)
MODELS = {
    'openai_b32': ('ViT-B-32', 'openai'),
    'laion2b_b32': ('ViT-B-32', 'laion2b_s34b_b79k'),
    'datacomp_b32': ('ViT-B-32', 'datacomp_xl_s13b_b90k'),
    'siglip_b16': ('ViT-B-16-SigLIP', 'webli'),
    'siglip2_b16': ('ViT-B-16-SigLIP2', 'webli'),
    'tinyclip_8m16': ('hf', 'wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M'),
    'tinyclip_40m32': ('hf', 'wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M'),
    'tinyclip_61m32': ('hf', 'wkcn/TinyCLIP-ViT-61M-32-Text-29M-LAION400M'),
}
def letterbox(im, size, mean):
    im = im.convert('RGB'); s = size / max(im.size)
    im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.BICUBIC)
    out = Image.new('RGB', (size, size), tuple(int(m * 255) for m in mean)); out.paste(im, ((size - im.width) // 2, (size - im.height) // 2)); return out
for name in sys.argv[1:]:
    arch, tag = MODELS[name]
    if arch == 'hf':
        from transformers import CLIPModel
        import torchvision.transforms as T
        hf = CLIPModel.from_pretrained(tag).eval()
        class W(torch.nn.Module):
            def encode_image(self, x): return hf.get_image_features(pixel_values=x)
        model = W()
        norm = T.Normalize((0.48145466, 0.4578275, 0.40821073), (0.26862954, 0.26130258, 0.27577711))
        pre = T.Compose([T.Resize(224, interpolation=T.InterpolationMode.BICUBIC), T.CenterCrop(224), lambda im: im.convert('RGB'), T.ToTensor(), norm])
    else:
        model, _, pre = open_clip.create_model_and_transforms(arch, pretrained=tag); model.eval()
    size = 224 if arch == "hf" else (pre.transforms[0].size if isinstance(pre.transforms[0].size, int) else pre.transforms[0].size[0])
    norm = pre.transforms[-1]
    import torchvision.transforms as T
    lb = T.Compose([lambda im: letterbox(im, size, norm.mean), T.ToTensor(), norm])
    for mode, tf in [('crop', pre), ('lb', lb)]:
        out = []; t0 = time.time()
        with torch.no_grad():
            for i in range(0, len(order), 64):
                x = torch.stack([tf(Image.open(f'img/{k}.jpg')) for k in order[i:i + 64]])
                e = model.encode_image(x); out.append(torch.nn.functional.normalize(e, dim=-1).numpy())
        np.save(f'emb/{name}_{mode}.npy', np.concatenate(out))
        print(name, mode, f'{(time.time() - t0) / len(order) * 1000:.1f} ms/img (batched, 12 threads)', flush=True)
