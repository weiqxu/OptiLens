#!/usr/bin/env python3
"""Install only the FE files from a checksum-pinned public StarRocks image layer."""
import argparse,concurrent.futures,hashlib,json,shutil,tarfile,time,urllib.request,urllib.error
from pathlib import Path
VERSION='4.0.14';COMMIT='7c18dd9dded1587dad24d912dcf8e777daf919f7'
REPOSITORY='starrocks/fe-ubuntu';DIGEST='sha256:c7b7fd890580258a9fafa6ef1c971279ee55bbf8ab1f0d83b733651b1c2cf423';SIZE=678850343

def install(home,workers):
    home.mkdir(parents=True,exist_ok=True);parts=home/'.download';parts.mkdir(exist_ok=True)
    def token():
        url='https://auth.docker.io/token?service=registry.docker.io&scope=repository:'+REPOSITORY+':pull&nonce='+str(time.time())
        return json.load(urllib.request.urlopen(url,timeout=30))['token']
    credentials=token();chunk=8*1024*1024;count=(SIZE+chunk-1)//chunk
    def fetch(i):
        nonlocal credentials
        first=i*chunk;last=min(SIZE,first+chunk)-1;target=parts/str(i)
        if target.exists() and target.stat().st_size==last-first+1:return
        for attempt in range(5):
            try:
                url='https://registry-1.docker.io/v2/'+REPOSITORY+'/blobs/'+DIGEST+'?nonce='+str(time.time())
                request=urllib.request.Request(url,headers={'Authorization':'Bearer '+credentials,'Range':f'bytes={first}-{last}'})
                with urllib.request.urlopen(request,timeout=90) as response:
                    if response.status!=206 or response.headers.get('Content-Range')!=f'bytes {first}-{last}/{SIZE}':raise ValueError('Invalid range response')
                    data=response.read(last-first+2)
                    if len(data)!=last-first+1:raise ValueError('Incomplete layer chunk')
                temp=parts/(str(i)+'.tmp');temp.write_bytes(data);temp.replace(target);return
            except Exception as error:
                if isinstance(error,urllib.error.HTTPError) and error.code==401:credentials=token()
                if attempt==4:raise
                time.sleep(attempt+1)
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for done,future in enumerate(concurrent.futures.as_completed([pool.submit(fetch,i) for i in range(count)]),1):
            future.result();print(f'Downloaded {done}/{count} chunks',flush=True)
    archive=parts/'layer.tar.gz';checksum=hashlib.sha256()
    with archive.open('wb') as output:
        for i in range(count):data=(parts/str(i)).read_bytes();checksum.update(data);output.write(data)
    if 'sha256:'+checksum.hexdigest()!=DIGEST:raise ValueError('Pinned runtime checksum does not match')
    with tarfile.open(archive,'r:gz') as source:
        for member in source:
            if not member.isfile():continue
            segments=Path(member.name).parts
            if '..' in segments:raise ValueError('Unsafe archive path')
            if 'fe' not in segments:continue
            destination=home/'fe'/Path(*segments[segments.index('fe')+1:]);destination.parent.mkdir(parents=True,exist_ok=True)
            with source.extractfile(member) as stream,destination.open('wb') as output:shutil.copyfileobj(stream,output)
    (home/'provenance.json').write_text(json.dumps(dict(version=VERSION,commit=COMMIT,repository=REPOSITORY,layer=DIGEST),indent=2)+'\n')
    print('Installed checksum-verified StarRocks '+VERSION+' in '+str(home))
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('home',type=Path);parser.add_argument('--workers',type=int,default=16);args=parser.parse_args()
    if not 1<=args.workers<=32:parser.error('--workers must be between 1 and 32')
    install(args.home.resolve(),args.workers)
