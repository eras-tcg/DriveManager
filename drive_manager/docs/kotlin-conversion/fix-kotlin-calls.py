# Kotlin callers: "Expression 'x' ... cannot be invoked as a function" at file:line:col -> drop the "()" after x
import re,sys,collections
errs=collections.defaultdict(set)
for line in sys.stdin:
    m=re.search(r"ERROR\] file://(\S+\.kt):(\d+):(\d+) Expression '(\w+)' of type .* cannot be invoked as a function",line)
    if m: errs[m.group(1)].add((int(m.group(2)),int(m.group(3)),m.group(4)))
fixed=0
for f,locs in errs.items():
    lines=open(f).read().split('\n')
    for ln,col,name in sorted(locs,reverse=True):
        s=lines[ln-1]; i=col-1
        if s[i:i+len(name)+2]==name+'()':
            lines[ln-1]=s[:i+len(name)]+s[i+len(name)+2:]; fixed+=1
        else: print('skip',f,ln,col,name)
    open(f,'w').write('\n'.join(lines))
print('fixed',fixed)
