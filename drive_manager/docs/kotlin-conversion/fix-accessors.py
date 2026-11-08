# Fixes Java calls to converted records' accessors (x() -> getX(), Type::x -> Type::getX) at the exact positions javac
# reports as "cannot find symbol" or "invalid method reference". Usage: fix-accessors.py <accessor names comma-separated> < mvn output
import re,sys,collections
names=set(sys.argv[1].split(','))
errs=collections.defaultdict(set)
for line in sys.stdin:
    m=re.search(r'ERROR\] (/\S+\.java):\[(\d+),(\d+)\] (?:cannot find symbol|invalid method reference)',line)
    if m: errs[m.group(1)].add((int(m.group(2)),int(m.group(3))))
fixed=0
for f,locs in errs.items():
    lines=open(f).read().split('\n')
    for ln,col in sorted(locs,reverse=True):
        s=lines[ln-1]
        i=col-1
        # javac points at the dot or at the name: find the identifier at or after the position
        m=re.compile(r'(\.|::)?\s*([a-zA-Z_]\w*)').match(s,i)
        if not m: continue
        name=m.group(2); start=m.start(2); end=m.end(2)
        if s[end:].startswith('::'):  # "invalid method reference" points at the type of Type::x
            m=re.compile(r'::\s*([a-zA-Z_]\w*)').match(s,end)
            name=m.group(1); start=m.start(1); end=m.end(1)
        if name not in names: continue
        rest=s[end:]
        if rest.startswith('()') or (m.group(1)=='::' or s[max(0,start-2):start]=='::'):
            new='get'+name[0].upper()+name[1:]
            lines[ln-1]=s[:start]+new+s[end:]; fixed+=1
    open(f,'w').write('\n'.join(lines))
print('fixed',fixed,'in',len(errs),'files')
