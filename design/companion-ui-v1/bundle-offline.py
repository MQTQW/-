"""Embed the preview wrapper's three dependencies for a self-contained export."""
from pathlib import Path
import html
import re

directory = Path(__file__).resolve().parent
target = directory / 'index.html'
document = target.read_text(encoding='utf-8')
match = re.search(r'(<iframe\b[^>]*data-srcdoc=")(.*?)(")', document, re.S)
assert match, 'Preview frame not found'
fragment = html.unescape(match.group(2))
dependencies = [
    ('https://unpkg.com/lucide@1.17.0/dist/umd/lucide.js', 'lucide.js'),
    ('https://unpkg.com/@floating-ui/core@1.7.3/dist/floating-ui.core.umd.min.js', 'floating-core.js'),
    ('https://unpkg.com/@floating-ui/dom@1.7.4/dist/floating-ui.dom.umd.min.js', 'floating-dom.js'),
]
for url, filename in dependencies:
    script = (directory / 'vendor' / filename).read_text(encoding='utf-8')
    script = script.replace('</script', '<\\/script')
    pattern = r'<script\b([^>]*)\bsrc="' + re.escape(url) + r'"([^>]*)></script>'
    fragment, count = re.subn(pattern, lambda m: '<script' + m.group(1).replace('async', '') + m.group(2) + '>' + script + '</script>', fragment)
    assert count == 1, (filename, count)
document = document[:match.start(2)] + html.escape(fragment, quote=True) + document[match.end(2):]
document = document.replace('<html lang="en"', '<html lang="zh-CN"').replace('<title>Companion Complete Design</title>', '<title>MY PLAN · 柔和陪伴设计预览</title>')
target.write_text(document, encoding='utf-8')
print('Bundled offline preview:', target.stat().st_size, 'bytes')
