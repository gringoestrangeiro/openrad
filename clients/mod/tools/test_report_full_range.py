"""Release report must reject inert/mismatched artifacts before packaging."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('release_report', Path(__file__).with_name('report-full-range.py'))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

class ReleaseReportTest(unittest.TestCase):
    def fixture(self, directory, mutation=None):
        path = Path(directory) / 'synthetic.jar'
        metadata = {'version': '2.3.0', 'environment': 'client',
            'entrypoints': {'client': ['io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod']},
            'mixins': ['openrad-client.mixins.json'],
            'depends': {'minecraft': '1.21.1', 'java': '>=21'}}
        names = ['ConnectionMixin', 'LegacyStatusPingerMixin', 'MultiplayerScreenMixin']
        mixins = {'package': 'io.github.gringoestrangeiro.openradclient.fabric.mixin',
            'client': names.copy(), 'compatibilityLevel': 'JAVA_21',
            'required': True, 'injectors': {'defaultRequire': 1}}
        if mutation == 'entrypoint': metadata['entrypoints'] = {}
        if mutation == 'mixin-reference': metadata['mixins'] = []
        if mutation == 'empty-mixins': mixins['client'] = []
        if mutation == 'optional': mixins['required'] = False
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('fabric.mod.json', json.dumps(metadata))
            z.writestr('openrad-client.mixins.json', json.dumps(mixins))
            for name in ['assets/openrad-client/lang/pt_br.json', 'assets/openrad-client/lang/ja_jp.json', 'META-INF/LICENSE']:
                z.writestr(name, '{}')
            classes = ['io/github/gringoestrangeiro/openradclient/fabric/OpenRadClientMod.class',
                'io/github/gringoestrangeiro/openradclient/ClientVpnRuntime.class']
            classes += [mixins['package'].replace('.', '/') + '/' + name + '.class' for name in names]
            for name in classes:
                major = 69 if mutation == 'additional-bytecode' and name.endswith('ClientVpnRuntime.class') else 65
                z.writestr(name, b'\xca\xfe\xba\xbe' + struct.pack('>HH', 0, major))
        return path

    def test_valid_release_is_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(report.check_mod(self.fixture(directory), '1.21.1', '2.3.0'), ('BUILD VERIFICADO', ''))

    def test_inert_or_incompatible_release_is_not_verified(self):
        for mutation in ['entrypoint', 'mixin-reference', 'empty-mixins', 'optional', 'additional-bytecode']:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                self.assertEqual(report.check_mod(self.fixture(directory, mutation), '1.21.1', '2.3.0')[0], 'NÃO VALIDADO')

if __name__ == '__main__':
    unittest.main()
