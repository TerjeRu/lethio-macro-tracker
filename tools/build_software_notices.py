"""Collect license texts and notices from the resolved Android runtime artifacts."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def collect(inventory, cache, mpl):
    texts = {}
    libraries = []

    def add(name, text):
        text = text.strip()
        if not text:
            return
        key = hashlib.sha256(text.encode()).hexdigest()
        entry = texts.setdefault(key, {'names': set(), 'text': text})
        entry['names'].add(name)

    for item in sorted(inventory, key=lambda x: (x['group'], x['name'], x['version'])):
        coordinate = '{group}:{name}:{version}'.format(**item)
        licenses = []
        for pom in (cache/item['group']/item['name']/item['version']).glob('*/*.pom'):
            for node in ET.parse(pom).findall('.//m:licenses/m:license', NS):
                licenses.append((node.findtext('m:name', namespaces=NS), node.findtext('m:url', namespaces=NS)))
        if not licenses:
            if item['group'].startswith(('androidx.', 'org.jetbrains.kotlin')) or coordinate.startswith(('javax.inject:', 'com.google.auto.value:', 'com.google.guava:')):
                licenses = [('Apache License 2.0', 'https://www.apache.org/licenses/LICENSE-2.0')]
            else:
                raise ValueError('Missing license metadata: '+coordinate)
        libraries.append(coordinate+'\n'+'\n'.join(name+'\n'+(url or '') for name, url in licenses))
        with zipfile.ZipFile(item['file']) as archive:
            archives = [archive]
            if 'classes.jar' in archive.namelist():
                archives.append(zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))))
            for z in archives:
                for name in z.namelist():
                    if name.endswith('/'):
                        continue
                    if name.endswith('third_party_licenses.json'):
                        index = json.loads(z.read(name))
                        body_name = name.removesuffix('.json')+'.txt'
                        if body_name in z.namelist():
                            body = z.read(body_name)
                            for component, span in index.items():
                                if not isinstance(span, dict):
                                    continue
                                start, length = span['start'], span['length']
                                if start < 0 or length < 0 or start+length > len(body):
                                    raise ValueError('Invalid license span: '+coordinate)
                                add(component, body[start:start+length].decode('utf-8'))
                    elif any(s in name.lower() for s in ('license', 'notice', 'copying')) and not name.endswith(('.json', '.class')):
                        data = z.read(name)
                        if b'\0' not in data and not name.endswith('third_party_licenses.txt'):
                            add(coordinate, data.decode('utf-8'))
            for z in archives[1:]:
                z.close()
    add('Runtime libraries', '\n\n'.join(libraries))
    add('SQLite', 'SQLite is in the public domain.\nhttps://www.sqlite.org/copyright.html')
    add('Mozilla Public License 2.0', mpl.read_text(encoding='utf-8'))
    notices = [{'id': key, 'name': '\n'.join(sorted(entry['names'])), 'text': entry['text']} for key, entry in texts.items()]
    notices.sort(key=lambda x: x['name'].lower())
    return {'artifacts': sorted('{group}:{name}:{version}'.format(**x) for x in inventory), 'notices': notices}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--cache', type=Path, default=Path.home()/'.gradle/caches/modules-2/files-2.1')
    parser.add_argument('--mpl', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    result = collect(json.loads(args.inventory.read_text(encoding='utf-8')), args.cache, args.mpl)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(f"Preserved {len(result['notices'])} notice entries for {len(result['artifacts'])} runtime artifacts")

if __name__ == '__main__':
    main()
