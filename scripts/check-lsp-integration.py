#!/usr/bin/env python3
"""Exercise the pinned binary over stdio; never changes a user's project."""
import argparse
import json
import os
import pathlib
import queue
import subprocess
import tempfile
import threading
import time

COMMANDS = ['shopware.openReferences', 'shopware.admin.extendComponent', 'shopware.admin.overrideMethod',
            'shopware.admin.overrideTwigBlock', 'shopware.twig.extendBlock', 'shopware.twig.showBlockDiff']

class Client:
    def __init__(self, executable, root):
        self.cache = tempfile.TemporaryDirectory(prefix='shopware-lsp-cache-check-')
        self.process = subprocess.Popen([str(executable)], cwd=root, env={**os.environ, 'SHOPWARE_LSP_CACHE_DIR': self.cache.name}, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.messages = queue.Queue()
        self.notifications = []
        self.identifier = 0
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        while True:
            headers = {}
            while (line := self.process.stdout.readline()) not in (b'\r\n', b'\n', b''):
                key, value = line.decode().split(':', 1)
                headers[key.lower()] = value.strip()
            if not line:
                self.messages.put({'error': 'Server exited'})
                return
            self.messages.put(json.loads(self.process.stdout.read(int(headers['content-length']))))

    def send(self, message):
        body = json.dumps({'jsonrpc': '2.0', **message}).encode()
        self.process.stdin.write(f'Content-Length: {len(body)}\r\n\r\n'.encode() + body)
        self.process.stdin.flush()

    def request(self, method, params=None):
        self.identifier += 1
        identifier = self.identifier
        self.send({'id': identifier, 'method': method, 'params': params})
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            message = self.messages.get(timeout=max(0.01, deadline-time.monotonic()))
            if message.get('id') == identifier and 'method' not in message:
                if 'error' in message:
                    raise RuntimeError(message['error'])
                return message.get('result')
            if 'id' in message and 'method' in message:
                self.send({'id': message['id'], 'result': None})
            else:
                self.notifications.append(message)
        raise TimeoutError(method)

    def notify(self, method, params):
        self.send({'method': method, 'params': params})

    def initialize(self, root, version=1):
        return self.request('initialize', {'processId': None, 'rootUri': root.as_uri(),
            'capabilities': {'window': {'workDoneProgress': True}, 'workspace': {'workspaceEdit': {'documentChanges': True, 'resourceOperations': ['create']}},
                             'textDocument': {'synchronization': {'didSave': True}}},
            'initializationOptions': {'configuration': {}, 'allowUnsupportedProject': False,
                'shopwareClient': {'protocolVersion': version, 'presentationProfile': 'framework', 'supportedCommands': COMMANDS}}})

    def wait_indexing(self, timeout=45):
        deadline = time.monotonic() + timeout
        while not any(item.get('method') == 'shopware/indexingCompleted' for item in self.notifications):
            if time.monotonic() >= deadline:
                raise TimeoutError('Workspace indexing')
            message = self.messages.get(timeout=max(0.01, deadline-time.monotonic()))
            assert message.get('method') != 'shopware/indexingFailed', message
            if 'id' in message and 'method' in message:
                self.send({'id': message['id'], 'result': None})
            else:
                self.notifications.append(message)

    def close(self):
        try:
            self.request('shutdown')
            self.notify('exit', None)
            self.process.wait(timeout=5)
            assert self.process.returncode == 0, self.process.returncode
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait()
            self.cache.cleanup()


def check(executable):
    with tempfile.TemporaryDirectory(prefix='shopware-lsp-check-') as temporary:
        root = pathlib.Path(temporary).resolve()
        client = Client(executable, root)
        try:
            result = client.initialize(root)
            state = result['capabilities']['experimental']['shopwareLSP']
            assert state['active'] is False and state['reason'] == 'unsupportedProject', state
        finally:
            client.close()
        print('PASS unsupported initialization and graceful shutdown', flush=True)
        (root/'composer.json').write_text(json.dumps({'name':'acme/integration-test','type':'shopware-platform-plugin',
            'require':{'shopware/core':'^6.7'}, 'autoload':{'psr-4':{'Acme\\Integration\\':'src/'}},
            'extra':{'shopware-plugin-class':'Acme\\Integration\\Integration'}}))
        (root/'src').mkdir()
        (root/'src/Integration.php').write_text('<?php\nnamespace Acme\\Integration;\nclass Integration {}\n')
        resources = root/'src/Resources'
        (resources/'snippet/en-GB').mkdir(parents=True)
        (resources/'snippet/en-GB/messages.en-GB.json').write_text(json.dumps({'acme': {'hello':'Hello 🛍️'}}, ensure_ascii=False))
        (resources/'views/storefront').mkdir(parents=True)
        template = resources/'views/storefront/example.html.twig'
        template.write_text("{{ 'acme.hello'|trans }}\n")
        client = Client(executable, root)
        try:
            result = client.initialize(root)
            state = result['capabilities']['experimental']['shopwareLSP']
            assert state['active'] and state['protocolVersion'] == 1 and state['presentationProfile'] == 'framework', state
            assert 'implementationProvider' not in result['capabilities'] and 'typeHierarchyProvider' not in result['capabilities']
            client.notify('initialized', {})
            client.wait_indexing()
            assert any(item.get('method') == '$/progress' for item in client.notifications), 'Missing indexing progress'
            catalog = client.request('shopware/integration/catalog', {})
            assert catalog['protocolVersion'] == 1
            assert set(COMMANDS) <= {command['id'] for command in catalog['clientCommands']}
            scaffolds = catalog['scaffolds']
            assert any(scaffold['workflow'] == 'entity-schema' for scaffold in scaffolds)
            bootstrap = client.request('shopware/entity-schema/bootstrap', {'directoryUri': root.as_uri()})
            assert bootstrap['fieldTypes'] and bootstrap['definitionKinds']
            entity_request = {'spec':bootstrap['spec'], 'decisions':[], 'documents':{}}
            entity_preview = client.request('shopware/entity-schema/preview', entity_request)
            assert not entity_preview.get('issues'), entity_preview.get('issues')
            if entity_preview.get('migrationTimestamp'):
                entity_request['spec']['migrationTimestamp'] = entity_preview['migrationTimestamp']
            entity_apply = client.request('shopware/entity-schema/apply', {**entity_request, 'revision':entity_preview['revision'], 'allowDestructive':False})
            assert entity_apply['edit'] and entity_apply['primaryFileUri'], entity_apply
            assert not list(root.rglob('*ExampleDefinition.php')), 'Entity server wrote files directly'
            created = client.request('shopware/scaffold/create', {'kind':'scheduled-task','directoryUri':(root/'src').as_uri(), 'name':'Cleanup','options':{'interval':300}})
            assert 'edit' in created and 'primaryFileUri' in created, created
            assert not list(root.rglob('*Cleanup*')), 'Server wrote files directly'
            config = client.request('shopware/configuration/catalog', {})
            assert config
            assert client.request('shopware/configuration/effective', {})
            file_uri = (root/'src/Integration.php').as_uri()
            client.notify('textDocument/didOpen', {'textDocument':{'uri':file_uri,'languageId':'php','version':1,'text':'<?php\n// 🛍️ unsaved\nnamespace Acme\\Integration;\nclass Integration {}\n'}})
            client.notify('textDocument/didChange', {'textDocument':{'uri':file_uri,'version':2},'contentChanges':[{'text':'<?php\n// updated 🛍️\nnamespace Acme\\Integration;\nclass Integration {}\n'}]})
            client.request('textDocument/hover', {'textDocument':{'uri':file_uri},'position':{'line':3,'character':8}})
            client.notify('textDocument/didClose', {'textDocument':{'uri':file_uri}})
            client.request('shopware/configuration/effective', {})
            client.notify('textDocument/didOpen', {'textDocument':{'uri':template.as_uri(),'languageId':'twig','version':1,'text':template.read_text()}})
            definition = client.request('textDocument/definition', {'textDocument':{'uri':template.as_uri()},'position':{'line':0,'character':8}})
            assert definition and definition[0]['uri'].endswith('messages.en-GB.json'), definition
            completion = client.request('textDocument/completion', {'textDocument':{'uri':template.as_uri()},'position':{'line':0,'character':9}})
            assert any(item['label'] == 'acme.hello' for item in completion['items']), completion
            hover = client.request('textDocument/hover', {'textDocument':{'uri':template.as_uri()},'position':{'line':0,'character':8}})
            assert 'Hello 🛍️' in hover['contents']['value'], hover
            hints = client.request('textDocument/inlayHint', {'textDocument':{'uri':template.as_uri()},'range':{'start':{'line':0,'character':0},'end':{'line':1,'character':0}}})
            assert any('Hello 🛍️' in part['value'] for hint in hints for part in hint['label']), hints
            client.notify('textDocument/didChange', {'textDocument':{'uri':template.as_uri(),'version':2},'contentChanges':[{'text':"{{ 'acme.missing'|trans }}\n"}]})
            diagnostics = client.request('textDocument/diagnostic', {'textDocument':{'uri':template.as_uri()}})
            assert any(item['code'] == 'frontend.snippet.missing' for item in diagnostics['items']), diagnostics
            client.notify('textDocument/didClose', {'textDocument':{'uri':template.as_uri()}})
            client.request('shopware/configuration/effective', {})
            assert any(item.get('method') == 'textDocument/publishDiagnostics' and item.get('params', {}).get('uri') == template.as_uri() and item['params']['diagnostics'] == [] for item in client.notifications), 'Diagnostics not cleared on close'
            print('PASS framework protocol, catalog, scaffold/entity preview and apply, Twig completion/definition/diagnostics and document lifecycle', flush=True)
        finally:
            client.close()
        client = Client(executable, root)
        try:
            try:
                client.initialize(root, 999)
                raise AssertionError('Protocol mismatch was accepted')
            except RuntimeError as error:
                assert 'protocol version' in str(error), error
        finally:
            client.process.kill()
            client.process.wait()
            client.cache.cleanup()
        print('PASS protocol mismatch rejected', flush=True)


def check_project(executable, root):
    """Opt-in read-only smoke test matching the LSP guide's sw-trunk fixture."""
    source = root/'src/Core/Framework/Demodata/DemodataContext.php'
    assert source.is_file(), f'Expected a Shopware source checkout: {source}'
    client = Client(executable, root)
    try:
        result = client.initialize(root)
        assert result['capabilities']['experimental']['shopwareLSP']['active']
        assert 'implementationProvider' not in result['capabilities'] and 'typeHierarchyProvider' not in result['capabilities']
        client.notify('initialized', {})
        print('Indexing opt-in Shopware project with an isolated temporary cache…', flush=True)
        client.wait_indexing(timeout=900)
        symbols = client.request('workspace/symbol', {'query':'SystemConfigService'})
        assert symbols, 'Missing Shopware workspace symbols'
        client.notify('textDocument/didOpen', {'textDocument':{'uri':source.as_uri(),'languageId':'php','version':1,'text':source.read_text()}})
        diagnostics = client.request('textDocument/diagnostic', {'textDocument':{'uri':source.as_uri()}})
        assert not any(str(item.get('code', '')).startswith('php.') or item.get('source', '').startswith('shopware-php') for item in diagnostics['items']), diagnostics
        client.notify('textDocument/didClose', {'textDocument':{'uri':source.as_uri()}})
        print('PASS Shopware source project indexing, framework symbols and duplicate PHP diagnostic suppression', flush=True)
    finally:
        client.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('executable', type=pathlib.Path)
    parser.add_argument('--project-root', type=pathlib.Path, help='Also run the read-only Shopware source checkout smoke test')
    args = parser.parse_args()
    check(args.executable.resolve())
    if args.project_root:
        check_project(args.executable.resolve(), args.project_root.resolve())
