"""Envia uma notificação Web Push criptografada (aes128gcm), igual o servidor do painel faz."""
import base64, hashlib, hmac, json, os, struct, sys, urllib.request
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import serialization

def d(s): return base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))
H = lambda k, m: hmac.new(k, m, hashlib.sha256).digest()

sub = json.load(open(sys.argv[1]))
sub = sub.get('sub') or sub
endpoint, ua_pub, auth = sub['endpoint'], d(sub['keys']['p256dh']), d(sub['keys']['auth'])
payload = json.dumps({"title": "Pix recebido", "body": "R$ 1.250,00 de Cliente Teste", "url": "/"}).encode()

as_priv = ec.generate_private_key(ec.SECP256R1())
as_pub = as_priv.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
ecdh = as_priv.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), ua_pub))
salt = os.urandom(16)
ikm = H(H(auth, ecdh), b'WebPush: info\x00' + ua_pub + as_pub + b'\x01')
prk = H(salt, ikm)
cek = H(prk, b'Content-Encoding: aes128gcm\x00\x01')[:16]
nonce = H(prk, b'Content-Encoding: nonce\x00\x01')[:12]
ct = AESGCM(cek).encrypt(nonce, payload + b'\x02', None)
body = salt + struct.pack('>I', 4096) + bytes([len(as_pub)]) + as_pub + ct

req = urllib.request.Request(endpoint, data=body, method='POST',
                             headers={'Content-Encoding': 'aes128gcm', 'TTL': '60', 'Content-Type': 'application/octet-stream'})
with urllib.request.urlopen(req, timeout=30) as r:
    print('enviado', r.status, endpoint.split('?')[0][:30] + '…')
