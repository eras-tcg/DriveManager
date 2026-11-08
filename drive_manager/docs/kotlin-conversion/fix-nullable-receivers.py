# Kotlin callers: "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver" at file:line:col
# (the column is the ".") -> insert "!!" there. Only where Java dereferenced the value anyway: review the diff.
# usage: mvn -q clean test-compile 2>&1 | python3 docs/kotlin-conversion/fix-nullable-receivers.py
import re, sys, collections
errs = collections.defaultdict(set)
for line in sys.stdin:
    m = re.search(r"ERROR\] file://(\S+\.kt):(\d+):(\d+) Only safe \(\?\.\) or non-null asserted", line)
    if m: errs[m.group(1)].add((int(m.group(2)), int(m.group(3))))
fixed = 0
for f, locs in errs.items():
    lines = open(f).read().split('\n')
    for ln, col in sorted(locs, reverse=True):
        s = lines[ln - 1]; i = col - 1
        if s[i] == '.':
            lines[ln - 1] = s[:i] + '!!' + s[i:]; fixed += 1
        else: print('skip', f, ln, col, s[i:i + 20])
    open(f, 'w').write('\n'.join(lines))
print('fixed', fixed)
