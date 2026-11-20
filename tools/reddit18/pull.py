import json,time,urllib.request,urllib.parse,sys,os
BASE="https://arctic-shift.photon-reddit.com/api/subreddits/search"
UA={'User-Agent':'StayClean-blocklist-builder/1.0 (open-source focus app; one-off build)'}
floor=int(sys.argv[1])
seen=json.load(open('arctic.json')) if os.path.exists('arctic.json') else {}
upper=int(sys.argv[2]) if len(sys.argv)>2 else 100_000_000
band=None; fails=0
def get(lo,hi):
    q=dict(over18='true',min_subscribers=lo,max_subscribers=hi,limit=1000,sort_type='subscribers',sort='desc',fields='display_name,subscribers,over18')
    req=urllib.request.Request(BASE+'?'+urllib.parse.urlencode(q),headers=UA)
    with urllib.request.urlopen(req,timeout=60) as r: return json.load(r)
while upper>=floor:
    if band is None: band=max(1,upper*3//4)
    lo=max(floor,upper-band)
    try: d=get(lo,upper); rows=d.get('data')
    except Exception as e: rows=None; d={'error':str(e)}
    if rows is None:
        fails+=1; band=max(1,band//2)
        print('fail',lo,upper,d.get('error'),file=sys.stderr)
        if fails>10: break
        time.sleep(3*fails); continue
    fails=0
    for x in rows:
        if x.get('over18'):
            n=x['display_name'].lower(); seen[n]=max(seen.get(n,0),x.get('subscribers') or 0)
    if len(rows)==1000:
        upper=min(x.get('subscribers') or 0 for x in rows)   # band full: resume from the smallest seen
        band=max(1,band//2)
    else:
        upper=lo-1; band=int(band*1.5)
    print(f'{lo}-{upper+0} rows={len(rows)} total={len(seen)}',file=sys.stderr)
    json.dump(seen,open('arctic.json','w'))
    time.sleep(1.0)
print('done',len(seen),'stopped at',upper)
