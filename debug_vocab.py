import struct
import os

path = 'app/src/main/assets/filters_vocab_multilingual.bin'
if not os.path.exists(path):
    print(f"File not found: {path}")
    exit(1)

with open(path, 'rb') as f:
    f.read(12 + 80*201*4) # Skip header and filters
    n_vocab_bytes = f.read(4)
    if not n_vocab_bytes:
        print("Empty vocab header")
        exit(1)
        
    n_vocab = struct.unpack('<I', n_vocab_bytes)[0]
    print(f"n_vocab: {n_vocab}")
    
    tokens = {}
    for i in range(n_vocab):
        l_bytes = f.read(4)
        if not l_bytes: break
        l = struct.unpack('<I', l_bytes)[0]
        t = f.read(l).decode('utf-8', errors='ignore')
        tokens[i] = t
        
    targets = [13, 15, 314, 477, 764, 775, 821, 1101, 3436, 50257, 50362, 50258, 50264]
    for tid in targets:
        print(f"Token {tid}: {tokens.get(tid)}")
