# A class of statics that became a Kotlin `object` cannot be star-imported ("Cannot import on demand from object"):
# replace `import <pkg>.<Object>.*` in the given Kotlin files with one import per public member the file uses.
# usage: python3 docs/kotlin-conversion/fix-star-imports.py EffectEngine,Compose src/main/kotlin/.../A.kt src/main/kotlin/.../B.kt
import re, sys, os
objects = sys.argv[1].split(',')
for f in sys.argv[2:]:
    s = open(f).read()
    pkg = re.search(r'^package (\S+)', s, re.M).group(1)
    for obj in objects:
        m = re.search(r'^import ((?:\w+\.)*%s)\.\*\n' % obj, s, re.M)
        if not m: continue
        src = open(os.path.join(os.path.dirname(f), obj + '.kt')).read() if os.path.exists(os.path.join(os.path.dirname(f), obj + '.kt')) else None
        if src is None: print('skip', f, obj, '(no', obj + '.kt next to it)'); continue
        names = set(re.findall(r'^    (?:@JvmStatic\s+)?(?:fun (?:<[^>]+> )?|const val |val |data class |class |enum class )(\w+)', src, re.M))
        names -= set(re.findall(r'^    private (?:const val |val |fun (?:<[^>]+> )?|data class |class )(\w+)', src, re.M))
        code = re.sub(r'^import .*$', '', s.replace(m.group(0), ''), flags=re.M)
        used = sorted(n for n in names if re.search(r'(?<![\w.])%s\b' % n, code))
        s = s.replace(m.group(0), ''.join('import %s.%s\n' % (m.group(1), n) for n in used))
        print(f, obj, len(used), 'imports')
    open(f, 'w').write(s)
