from pathlib import Path

p = Path('app/src/main/java/app/tellev/core/i18n/S.kt')
lines = p.read_text(encoding='utf-8').split('\n')

# 1) 找到 idByName 的收尾 "    )"（其后紧跟 fallbackZh 注释）
id_close = None
for i, l in enumerate(lines):
    if l == '    )' and i + 1 < len(lines) and lines[i + 1].strip().startswith('/** JVM 单测回退'):
        id_close = i
        break
assert id_close is not None, 'idByName close not found'

# 2) 浮动块：fallbackZh 注释之后、直到第一条 zh 条目之前，全部是 "key" to R.string.key 行
comment = id_close + 1
assert lines[comment].strip().startswith('/** JVM 单测回退'), lines[comment]
float_start = comment + 1
float_end = float_start
while float_end < len(lines) and '" to R.string.' in lines[float_end]:
    float_end += 1
floating = lines[float_start:float_end]
assert floating and all('to R.string.' in l for l in floating), floating[:3]

# 3) 浮动块移回 idByName 内（收尾 ) 之前）
lines[id_close:id_close] = floating
# 行号整体后移 len(floating)
float_start += len(floating)
float_end += len(floating)
comment += len(floating)
id_close += len(floating)

# 4) 删掉原位置的浮动块
del lines[float_start:float_end]

# 5) 在注释与 zh 条目之间补回 mapOf 开头
assert lines[comment].strip().startswith('/** JVM 单测回退'), lines[comment]
assert '" to "' in lines[comment + 1], lines[comment + 1]
lines.insert(comment + 1, '    val fallbackZh: Map<String, String> = mapOf(')

p.write_text('\n'.join(lines), encoding='utf-8')
print('repaired: moved %d id entries back into idByName, restored fallbackZh header' % len(floating))
