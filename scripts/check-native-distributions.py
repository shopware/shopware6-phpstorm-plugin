#!/usr/bin/env python3
"""Validate native archive routing, executable integrity, and the absence of legacy providers."""
import hashlib
import io
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

targets = ['mac-arm64', 'mac-x86_64', 'linux-arm64', 'linux-x86_64', 'windows-x86_64']
manifest = dict(line.split('=', 1) for line in (Path(__file__).resolve().parents[1]/'gradle/shopware-lsp.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
for target in targets:
    paths = list(Path(sys.argv[1]).glob(f'*-{target}.zip'))
    assert len(paths) == 1, (target, paths)
    with zipfile.ZipFile(paths[0]) as archive:
        names = archive.namelist()
        executables = [name for name in names if '/shopware-lsp/' in name and name.endswith(('/shopware-lsp', '/shopware-lsp.exe'))]
        assert len(executables) == 1 and f'/{target}/' in executables[0], executables
        executable = executables[0]
        prefix = executable.rsplit('/', 1)[0]
        assert hashlib.sha256(archive.read(executable)).hexdigest() == archive.read(prefix+'/sha256.txt').decode().strip()
        assert archive.read(prefix+'/version.txt').decode().strip() == manifest['version']
        assert any(name.endswith('/shopware-lsp/entityDesignerWebview.js') for name in names)
        descriptors = []
        for name in names:
            if name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as jar:
                    if 'META-INF/plugin.xml' in jar.namelist():
                        descriptor = ET.fromstring(jar.read('META-INF/plugin.xml'))
                        if descriptor.findtext('id') == 'de.shyim.shopware6':
                            descriptors.append(descriptor)
        assert len(descriptors) == 1
        descriptor = descriptors[0]
        assert descriptor.findtext('version').endswith('-'+target)
        os_name, arch = target.split('-', 1)
        dependencies = [item.text for item in descriptor.findall('depends')]
        assert f'com.intellij.modules.os.{os_name}' in dependencies
        assert f'com.intellij.modules.arch.{arch}' in dependencies
        assert 'com.intellij.modules.lsp' in dependencies
        assert descriptor.find('.//platform.lsp.integrationProvider') is not None
        assert descriptor.find('.//fileBasedIndex') is None
        assert descriptor.find('.//completion.contributor') is None
        assert descriptor.find('.//localInspection') is None
    print(f'PASS {target}: routing, checksum, shared designer and LSP-only registrations')
