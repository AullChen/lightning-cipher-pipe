"""Independent Python 3 stdlib oracle; never imports or executes production Java."""
import hashlib
import json
from pathlib import Path
import struct
import uuid
import base64

HERE = Path(__file__).resolve().parent

def cbor(value):
    if isinstance(value, int):
        major, n, body = 0, value, b''
    elif isinstance(value, bytes):
        major, n, body = 2, len(value), value
    elif isinstance(value, str):
        body = value.encode('ascii')
        major, n = 3, len(body)
    elif isinstance(value, list):
        major, n, body = 4, len(value), b''.join(map(cbor, value))
    else:
        raise TypeError(type(value))
    assert 0 <= n <= 2**63 - 1
    if n < 24:
        prefix = bytes([major * 32 + n])
    else:
        size, code = next((size, code) for size, code in [(1, 24), (2, 25), (4, 26), (8, 27)] if n < 256**size)
        prefix = bytes([major * 32 + code]) + n.to_bytes(size, 'big')
    return prefix + body

def sha(data):
    return hashlib.sha256(data).digest()

def tree(leaves):
    if not leaves:
        return sha(b'')
    if len(leaves) == 1:
        return leaves[0]
    split = 1 << ((len(leaves) - 1).bit_length() - 1)
    return sha(b'\x01' + tree(leaves[:split]) + tree(leaves[split:]))

transfer = uuid.UUID('00112233-4455-4677-8899-aabbccddeeff')
command = uuid.UUID('11223344-5566-4788-99aa-bbccddeeff00')
policy = [0, 1, 262144, 262144, 262144, 1, 1, 1, 1, 1, 1]
limits = [9437184, 8388608, 1000000, 1099511627776, 16]
request = ['lcp-stream-open/1', transfer.bytes, 'demo', 'source-a', 'target-a', bytes(range(32)), 'source-key', bytes([17])*32, 'target-key', bytes([34])*32, [0, 1], policy, limits, 1700000000000, 1700000060000]
accepted = ['lcp-stream-accepted/1', transfer.bytes, bytes([51])*32, 'handle-1', 0, policy, limits, 1700000060000]
binding = sha(cbor(['lcp-stream-binding/1', request, accepted, bytes([68])*32, bytes([85])*32]))
response = [accepted, sha(cbor(request)), binding]
chunk_aad = ['lcp-stream-chunk/1', transfer.bytes, binding, 0, 0, 1, 1, 0]
finish_aad = ['lcp-stream-finish-aad/1', transfer.bytes, binding, command.bytes]
vectors = {}
def vector(name, value):
    encoded = cbor(value)
    vectors[name + '.cbor'] = encoded.hex()
    vectors[name + '.sha256'] = sha(encoded).hex()
    return encoded

for name, value in [('policy', policy), ('limits', limits), ('open', request), ('accepted', accepted), ('response', response), ('chunk-aad', chunk_aad), ('finish-aad', finish_aad), ('hpke-info', ['lcp-stream-hpke/1', binding, 0]), ('cancel', ['lcp-stream-cancel/1', transfer.bytes, command.bytes, binding])]:
    vector(name, value)
vectors['binding.sha256'] = binding.hex()
for name, chunks in [('empty', []), ('one', [b'\x00']), ('three', [b'\x00', b'\x01\x02', b'\x03\x04\x05'])]:
    offset, leaves = 0, []
    for index, chunk in enumerate(chunks):
        leaves.append(sha(b'\x00' + cbor([index, offset, len(chunk), sha(chunk)])))
        offset += len(chunk)
    root = tree(leaves)
    vectors[name + '.root'] = root.hex()
    vector(name + '-finish', ['lcp-stream-finish/1', transfer.bytes, binding, command.bytes, len(chunks), offset, root])

# Framing-only fixtures: enc/ciphertext deliberately synthetic, NOT valid HPKE.
for name, kind, aad, cipher in [('chunk-frame', 0, cbor(chunk_aad), bytes(range(17))), ('finish-frame', 1, cbor(finish_aad), bytes(16 + len(bytes.fromhex(vectors['empty-finish.cbor']))))]:
    header = struct.pack('>4sBBIHI', b'LCPS', 1, kind, len(aad), 32, len(cipher))
    assert len(header) == 16
    vectors[name + '.hex'] = (header + aad + bytes(range(32)) + cipher).hex()

(HERE / 'golden.properties').write_text('# Generated only by reference.py; synthetic frames are not HPKE vectors.\n' + ''.join(f'{key}={value}\n' for key, value in vectors.items()), encoding='ascii', newline='\n')

def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b'=').decode('ascii')
policy_names = ['mode', 'policyVersion', 'minChunkBytes', 'maxChunkBytes', 'initialChunkBytes', 'minWindow', 'maxWindow', 'initialWindow', 'minZstdLevel', 'maxZstdLevel', 'initialZstdLevel']
policy_json = {key: str(v) if 2 <= i <= 7 else v for i, (key, v) in enumerate(zip(policy_names, policy))}
limits_json = dict(zip(['maxFrameBytes', 'maxPlainBytes', 'maxChunks', 'maxTransferBytes', 'maxInFlightChunks'], map(str, limits)))
request_json = dict(transferId=str(transfer), routeId='demo', sourceNodeId='source-a', targetNodeId='target-a', sourceChallenge=b64(bytes(range(32))), sourceKeyId='source-key', sourcePublicKeyHash=b64(bytes([17])*32), targetKeyId='target-key', targetPublicKeyHash=b64(bytes([34])*32), compressionOffers=[0, 1], policy=policy_json, limits=limits_json, createdAt='1700000000000', expiresAt='1700000060000')
(HERE / 'open.json').write_text(json.dumps(request_json, separators=(',', ':')) + '\n', encoding='ascii', newline='\n')
(HERE / 'open-reordered.json').write_text(json.dumps(dict(reversed(list(request_json.items()))), indent=2) + '\n', encoding='ascii', newline='\n')
print('Wrote independent Open/Finish/AAD/hash/frame vectors and two JSON projections.')
