import sys, os, zlib, struct, itertools

def load_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a png'
    pos = 8
    idat = b''
    w = h = wd = ct = None
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        body = data[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, wd, ct = struct.unpack('>IIBB', body[:10])
        elif typ == b'IDAT':
            idat += body
        elif typ == b'IEND':
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    bpp = ch * (wd // 8)
    stride = w * bpp
    out = bytearray()
    prev = bytearray(stride)
    p = 0
    for _ in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        for i in range(stride):
            a = line[i-bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i-bpp] if i >= bpp else 0
            x = line[i]
            if f == 1: x += a
            elif f == 2: x += b
            elif f == 3: x += (a + b) // 2
            elif f == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp-a), abs(pp-b), abs(pp-c)
                x += a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
            line[i] = x & 0xFF
        out += line
        prev = line
    return w, h, wd, ct, ch, bytes(out)

d = sys.argv[1]
names = ['multi_directrix_0', 'multi_directrix_1', 'multi_directrix_2']
imgs = {}
for n in names:
    for s in ('', '_energized'):
        p = os.path.join(d, n + s + '.png')
        w, h, wd, ct, ch, px = load_png(p)
        imgs[n + s] = (w, h, ch, px)
        if s == '':
            print(f'{n:20} {w}x{h} depth={wd} colortype={ct} channels={ch}')

print('\n--- 不透明性 / 尺寸 ---')
for k, (w, h, ch, px) in imgs.items():
    if ch == 4:
        alphas = set(px[3::4])
        print(f'{k:32} alpha值集合={sorted(alphas)} {"全不透明 OK" if alphas == {255} else "!! 有透明/半透明"}')
    else:
        print(f'{k:32} 无 alpha 通道 (ch={ch}) -> 必然不透明 OK')

print('\n--- 像素差 (0 = 完全相同) ---')
for a, b in itertools.combinations(imgs, 2):
    pa, pb = imgs[a][3], imgs[b][3]
    print(f'{a:32} vs {b:32} {sum(1 for x, y in zip(pa, pb) if x != y)}')

print('\n--- _X 与 _X_energized 的 alpha 轮廓是否一致 ---')
for n in names:
    pa, pb = imgs[n][3], imgs[n + '_energized'][3]
    same = all(pa[i] == pb[i] for i in range(3, len(pa), 4))
    print(f'{n:20} silhouette_identical={same}')

print('\n--- 三个"未激活"图的 ASCII (亮=# 中=+ 暗=.) ---')
for n in names:
    w, h, ch, px = imgs[n]
    print(f'[{n}]')
    for y in range(h):
        row = ''
        for x in range(w):
            i = (y * w + x) * ch
            lum = (px[i] * 299 + px[i+1] * 587 + px[i+2] * 114) // 1000
            row += '#' if lum > 170 else ('+' if lum > 85 else '.')
        print('  ' + row)
