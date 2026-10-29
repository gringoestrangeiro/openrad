"""Validate the Windows matrix script's artifact selection with a stubbed build."""
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.name == 'nt' and (shutil.which('pwsh') or shutil.which('powershell')), 'Windows PowerShell build script')
class MinecraftBuildMatrixTests(unittest.TestCase):
    def test_current_project_version_wins_over_stale_artifact(self):
        with tempfile.TemporaryDirectory(prefix='openrad-matrix-') as directory:
            root = Path(directory)
            (root / 'release').mkdir()
            shutil.copyfile(ROOT / 'clients/mod/build-matrix.ps1', root / 'build-matrix.ps1')
            shutil.copyfile(ROOT / 'clients/mod/build.gradle.kts', root / 'build.gradle.kts')
            (root / 'tools').mkdir()
            shutil.copyfile(ROOT / 'clients/mod/tools/verify-version-jar.py', root / 'tools/verify-version-jar.py')
            # Gradle itself is replaced; artifact inspection and copying are real.
            (root / 'gradlew.bat').write_text('@exit /b 0\n', encoding='ascii')
            libs = root / 'fabric/build/libs'
            libs.mkdir(parents=True)
            mixin_package = 'io/github/gringoestrangeiro/openradclient/fabric/mixin/'
            for version in ('2.2.0', '0.2.3-alpha'):
                with zipfile.ZipFile(libs / f'fabric-{version}.jar', 'w') as jar:
                    jar.writestr('fabric.mod.json', json.dumps({'version': version, 'environment': 'client',
                        'entrypoints': {'client': ['io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod']},
                        'mixins': ['openrad-client.mixins.json'], 'depends': {'minecraft': '1.21.1', 'java': '>=21'}}))
                    mixins = ['ConnectionMixin', 'LegacyStatusPingerMixin', 'MultiplayerScreenMixin']
                    jar.writestr('openrad-client.mixins.json', json.dumps({'package': mixin_package[:-1].replace('/', '.'),
                        'client': mixins, 'compatibilityLevel': 'JAVA_21', 'required': True, 'injectors': {'defaultRequire': 1}}))
                    jar.writestr('META-INF/LICENSE', 'synthetic')
                    names = ['io/github/gringoestrangeiro/openradclient/fabric/OpenRadClientMod.class',
                             'io/github/gringoestrangeiro/openradclient/ClientVpnRuntime.class']
                    names += [mixin_package + name + '.class' for name in mixins]
                    for name in names:
                        jar.writestr(name, b'\xca\xfe\xba\xbe' + struct.pack('>HH', 0, 65))
            result = subprocess.run([shutil.which('pwsh') or shutil.which('powershell'), '-NoProfile', '-File',
                str(root / 'build-matrix.ps1'), '-MinecraftVersions', '1.21.1'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertTrue((root / 'release/OpenRadClientMod-2.2.0-fabric-1.21.1.jar').is_file(), result.stdout)
            self.assertFalse((root / 'release/OpenRadClientMod-0.2.3-alpha-fabric-1.21.1.jar').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
