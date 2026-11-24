"""Fetch pinned public weights on the PC; Android never downloads models."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib, json, struct, urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/src/main/assets/ml/semantic'
STAGE = ROOT / '.qa/semantic-downloads'
TEXT = 'https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/58edf8cada9e398793dca955574a48cbb7f18be2/'
VISION = 'https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/6b7c523703ae26e39be4b03774966f8571c7f523/'
FILES = [
    ('text.onnx', TEXT + 'onnx/model_qint8_arm64.onnx', '3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7'),
    ('vision.onnx', VISION + 'onnx/vision_model_quantized.onnx', '583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299'),
    ('projection.safetensors', TEXT + '2_Dense/model.safetensors', 'd12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74'),
    ('wordpiece.txt', TEXT + 'vocab.txt', 'fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c'),
]

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def fetch(spec):
    name, url, expected = spec
    target = (STAGE if name.endswith('.safetensors') else OUT) / name
    if target.exists() and digest(target) == expected:
        return {'name': name, 'url': url, 'sha256': expected, 'bytes': target.stat().st_size}
    temporary = target.with_suffix(target.suffix + '.part')
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
        while block := response.read(1024 * 1024):
            output.write(block)
    if digest(temporary) != expected:
        raise ValueError('Public model checksum mismatch: ' + name)
    temporary.replace(target)
    print('Verified', name, target.stat().st_size, flush=True)
    return {'name': name, 'url': url, 'sha256': expected, 'bytes': target.stat().st_size}

def main():
    OUT.mkdir(parents=True, exist_ok=True); STAGE.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        receipt = list(pool.map(fetch, FILES))
    raw = (STAGE / 'projection.safetensors').read_bytes()
    header_size = struct.unpack('<Q', raw[:8])[0]
    header = json.loads(raw[8:8+header_size])['linear.weight']
    assert header['dtype'] == 'F32' and header['shape'] == [512, 768]
    start, end = header['data_offsets']; projection = raw[8+header_size+start:8+header_size+end]
    assert len(projection) == 512 * 768 * 4
    (OUT / 'projection.f32').write_bytes(projection)
    receipt.append({'name': 'projection.f32', 'sha256': hashlib.sha256(projection).hexdigest(), 'bytes': len(projection)})
    (OUT / 'PROVENANCE.json').write_text(json.dumps({'models': receipt, 'text_license': 'Apache-2.0', 'vision_license': 'MIT', 'processing': 'CPU on device; no network permission; pinned weights, no user data uploaded'}, indent=2) + '\n')
    for name, url in [('LICENSE-TEXT.txt','https://raw.githubusercontent.com/UKPLab/sentence-transformers/master/LICENSE'),('LICENSE-VISION.txt','https://raw.githubusercontent.com/openai/CLIP/main/LICENSE'),('LICENSE-RUNTIME.txt','https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.0/LICENSE')]:
        (OUT / name).write_bytes(urllib.request.urlopen(url, timeout=30).read())
    print('All semantic assets verified', flush=True)

def topics():
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer
    raw = urllib.request.urlopen(TEXT + 'tokenizer.json', timeout=30).read()
    assert hashlib.sha256(raw).hexdigest() == '5b4e1a8171c81dfd666ae40265b9530c6e0b3d53923fe8ac493dcc84229adf81'
    tokenizer = Tokenizer.from_str(raw.decode())
    options = ort.SessionOptions(); options.intra_op_num_threads = 2; options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(OUT/'text.onnx'), options, providers=['CPUExecutionProvider'])
    projection = np.fromfile(OUT/'projection.f32',dtype='<f4').reshape(512,768)
    labels = [
        ('naturaleza','Naturaleza','Un paisaje natural con montañas, un bosque o una playa.'),
        ('personas','Personas','Una fotografía de personas o un retrato de una persona.'),
        ('animales','Animales','Una fotografía de un animal, un perro o un gato.'),
        ('comida','Comida','Una fotografía de comida, platos, frutas o bebidas.'),
        ('espacios','Espacios','Una fotografía de edificios, calles, arquitectura o interiores.'),
        ('vehiculos','Vehículos','Una fotografía de un automóvil, una moto o un vehículo.'),
        ('documentos','Texto y capturas','Una captura de pantalla, un documento o una página con texto.'),
        ('arte','Arte y dibujos','Una ilustración, un dibujo, una pintura o una obra de arte.'),
        ('objetos','Objetos','Una fotografía de objetos, herramientas y productos.'),
        ('ropa','Ropa','Una fotografía de ropa, zapatos y accesorios de vestir.'),
        ('deportes','Deporte','Una fotografía de deportes, ejercicio o actividad física.'),
        ('celebraciones','Celebraciones','Una fotografía de una fiesta, una celebración o un concierto.'),
    ]
    with (OUT/'topics.bin').open('wb') as output:
        output.write(struct.pack('>i',len(labels)))
        for key,title,prompt in labels:
            ids=np.array([tokenizer.encode(prompt).ids],dtype=np.int64)
            hidden=session.run(['last_hidden_state'],{'input_ids':ids,'attention_mask':np.ones_like(ids)})[0]
            vector=projection @ hidden[0].mean(axis=0);vector/=np.linalg.norm(vector)
            for text in (key,title):
                utf8=text.encode('utf-8');output.write(struct.pack('>H',len(utf8)));output.write(utf8)
            output.write(struct.pack('>512f',*vector))
    print('Verified local topic vectors',digest(OUT/'topics.bin'),flush=True)

if __name__ == '__main__':
    import sys
    topics() if '--topics' in sys.argv else main()
