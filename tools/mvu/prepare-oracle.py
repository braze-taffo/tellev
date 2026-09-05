"""Extract exact reference revisions into ignored build output, never modify reference repositories."""
from pathlib import Path
import subprocess, tarfile, io, json, hashlib

root = Path(__file__).resolve().parents[2]
workspace = root.parent
manifest = json.loads((root / 'app/src/main/assets/compat/manifest.json').read_text(encoding='utf-8'))
output = root / 'build/mvu-oracle'
sources = [('sillytavern', 'SillyTavern', output / 'SillyTavern'),
           ('tavern_helper', 'js-slash-runner', output / 'SillyTavern/public/scripts/extensions/third-party/tavern-helper'),
           ('prompt_template', 'ST-Prompt-Template', output / 'SillyTavern/public/scripts/extensions/third-party/prompt-template')]
provenance = {}
for key, source, destination in sources:
    revision = manifest['upstream'][key]
    marker = destination / '.tellev-oracle-revision'
    if marker.exists():
        if marker.read_text().strip() != revision:
            raise RuntimeError(f'Existing oracle has another revision: {destination}')
        provenance[key] = revision
        continue
    data = subprocess.check_output(['git', '-C', str(workspace / source), 'archive', revision])
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        archive.extractall(destination, filter='data')
    marker.write_text(revision)
    provenance[key] = revision
config = output / 'SillyTavern/config.yaml'
if not config.exists():
    config.write_text('dataRoot: ./data\nlisten: false\nport: 18181\nbrowserLaunch:\n  enabled: false\nextensions:\n  enabled: true\n  autoUpdate: false\n',encoding='utf-8')
(output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8')
print('Prepared isolated upstream oracle:', output)
