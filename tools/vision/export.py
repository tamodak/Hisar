"""Exports the fine-tuned classifier for the app:

- body_classifier.tflite: CLIP ViT-B/32 (OpenAI) with its last blocks from tail8.pt; takes a
  [1, 224, 224, 3] float32 RGB image 0..255 (CLIP's normalisation built in) and returns the
  L2-normalised 512-float embedding. Weights int8, activations float (dynamic range), as photo-filter.
- body_head.bin: the head, 512 weights then the bias, little-endian float32, applied to the
  embedding (the x10 scale of training folded into the weights).
"""
import sys, tempfile, pathlib, numpy as np, torch, open_clip, litert_torch
from ai_edge_quantizer import quantizer, recipe
sys.path.insert(0, '/home/omer/omer/android/photo-filter/tools')
OUT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '.')
N = 8
MEAN = torch.tensor([0.48145466, 0.4578275, 0.40821073]).view(1, 3, 1, 1)
STD = torch.tensor([0.26862954, 0.26130258, 0.27577711]).view(1, 3, 1, 1)

class ExplicitAttention(torch.nn.Module):
    """nn.MultiheadAttention as plain 4D matmuls (photo-filter's convert_clip_image_encoder.py)."""
    def __init__(self, mha):
        super().__init__()
        self.heads, self.head_dim = mha.num_heads, mha.embed_dim // mha.num_heads
        self.batch_first = mha.batch_first
        self.qkv = torch.nn.Linear(mha.embed_dim, 3 * mha.embed_dim)
        self.qkv.weight.data = mha.in_proj_weight.data.clone(); self.qkv.bias.data = mha.in_proj_bias.data.clone()
        self.out = mha.out_proj
    def forward(self, q, k=None, v=None, need_weights=False, attn_mask=None):
        x = q if self.batch_first else q.transpose(0, 1)
        b, t, c = x.shape
        qkv = self.qkv(x)
        s = [qkv[..., i * c:(i + 1) * c].reshape(b, t, self.heads, self.head_dim).transpose(1, 2) for i in range(3)]
        a = torch.softmax((s[0] @ s[1].transpose(-1, -2)) * self.head_dim ** -0.5, dim=-1)
        y = self.out((a @ s[2]).transpose(1, 2).reshape(b, t, c))
        return (y if self.batch_first else y.transpose(0, 1)), None

class Encoder(torch.nn.Module):
    def __init__(self, visual):
        super().__init__(); self.visual = visual
    def forward(self, rgb):
        f = self.visual((rgb / 255.0 - MEAN) / STD)
        return f / torch.sqrt((f * f).sum(-1, keepdim=True))

model, _, _ = open_clip.create_model_and_transforms('ViT-B-32', pretrained='openai'); model.eval()
v = model.visual
state = torch.load(f'tail{N}.pt')
for i, blk in enumerate(v.transformer.resblocks[N:]):
    blk.load_state_dict({k[len(f'blocks.{i}.'):]: t for k, t in state.items() if k.startswith(f'blocks.{i}.')})
v.ln_post.load_state_dict({k[3:]: t for k, t in state.items() if k.startswith('ln.')})
v.proj.data = state['proj']
w = (state['head.weight'][0] * 10).numpy().astype('<f4'); b = state['head.bias'].numpy().astype('<f4')
np.concatenate([w, b]).tofile(OUT / 'body_head.bin')
for blk in v.transformer.resblocks: blk.attn = ExplicitAttention(blk.attn)
enc = Encoder(v).eval()
x = torch.rand(1, 224, 224, 3) * 255
ref = enc(x.permute(0, 3, 1, 2))
channel_last = litert_torch.to_channel_last_io(enc, args=[0])
with tempfile.TemporaryDirectory() as tmp:
    litert_torch.convert(channel_last, (x,)).export(f'{tmp}/f.tflite')
    quantizer.Quantizer(f'{tmp}/f.tflite', recipe.dynamic_wi8_afp32()).quantize().export_model(str(OUT / 'body_classifier.tflite'), overwrite=True)
from ai_edge_litert.interpreter import Interpreter
it = Interpreter(str(OUT / 'body_classifier.tflite')); it.allocate_tensors()
it.set_tensor(it.get_input_details()[0]['index'], x.numpy()); it.invoke()
got = it.get_tensor(it.get_output_details()[0]['index'])
print('cosine to float model', float((torch.from_numpy(got) * ref).sum()), 'size MB', (OUT / 'body_classifier.tflite').stat().st_size / 1e6)
