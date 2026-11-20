"""Pack the adult-community list into reddit18.bin (Golomb-Rice coded SHA-256/32 fingerprints).
Input: arctic.json (name -> members, Reddit over18 communities) and sfw.json (ordinary communities, for checks).
Names caught by rules.py are not stored; allowlisted names are never stored."""
import json,hashlib,struct,sys
from rules import rule,ALLOW
a=json.load(open('arctic.json')); s=json.load(open('sfw.json'))
h=lambda n:int.from_bytes(hashlib.sha256(n.encode()).digest()[:4],'big')
store=sorted({h(n) for n in a if not rule(n) and n not in ALLOW})
S=set(store)
assert not [n for n in ALLOW if h(n) in S], 'allowlisted name collides with the list'
coll=[n for n in s if h(n) in S]
assert not coll, ('ordinary communities collide', coll)
def enc(hs,k):
    out=bytearray();acc=0;nb=0;prev=0
    def put(bit):
        nonlocal acc,nb
        acc=(acc<<1)|bit;nb+=1
        if nb==8: out.append(acc);acc=0;nb=0
    for x in hs:
        g=x-prev;prev=x
        for _ in range(g>>k): put(1)
        put(0)
        for i in range(k-1,-1,-1): put((g>>i)&1)
    if nb: out.append(acc<<(8-nb))
    return bytes(out)
k=min(range(14,20),key=lambda k:len(enc(store,k)))
open(sys.argv[1] if len(sys.argv)>1 else 'reddit18.bin','wb').write(b'SC18'+struct.pack('>BI',k,len(store))+enc(store,k))
print('stored',len(store),'k',k,'rules',sum(1 for n in a if rule(n)),'allowlisted',sum(1 for n in a if n in ALLOW))
