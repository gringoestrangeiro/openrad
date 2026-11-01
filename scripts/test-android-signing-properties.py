"""Exercise the production writer with Java's real Properties parser and synthetic values."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('signing_properties', Path(__file__).with_name('write-android-signing-properties.py'))
writer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(writer)


@unittest.skipUnless(shutil.which('java'), 'JDK 17+ required to validate Java Properties parsing')
class SigningPropertiesTests(unittest.TestCase):
    def test_java_parser_preserves_synthetic_signing_values(self):
        with tempfile.TemporaryDirectory(prefix='openrad-properties-') as directory:
            directory = Path(directory)
            java = directory / 'CheckProperties.java'
            java.write_text('''import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
class CheckProperties {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) { p.load(input); }
        for (String name : new String[] {"storeFile", "storePassword", "keyAlias", "keyPassword"}) {
            if (!p.getProperty(name).equals(System.getenv("EXPECTED_" + name)))
                throw new AssertionError("Properties round-trip failed for " + name);
        }
    }
}''', encoding='utf-8')
            values = ['plain', r'fixture\password', ' leading trailing ', 'tab\tvalue',
                      'line\nbreak\r\nnext', 'form\ffeed', '=:# !', 'ação日本', 'supplementary😀']
            for index, value in enumerate(values):
                with self.subTest(case=index):
                    output = directory / f'case{index}.properties'
                    env = dict(os.environ, RELEASE_STORE_PASSWORD=value,
                               RELEASE_KEY_ALIAS=value, RELEASE_KEY_PASSWORD=value)
                    keystore = Path(r'C:\synthetic path\key.jks')
                    writer.write_properties(output, keystore, env)
                    env.update(EXPECTED_storeFile=str(keystore), EXPECTED_storePassword=value,
                               EXPECTED_keyAlias=value, EXPECTED_keyPassword=value)
                    result = subprocess.run(['java', str(java), str(output)], env=env, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    with self.assertRaises(FileExistsError):
                        writer.write_properties(output, keystore, env)


if __name__ == '__main__':
    unittest.main(verbosity=2)
