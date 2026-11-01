"""Publication checks must reject private content and exclude historical artifacts."""
from pathlib import Path
import tempfile
import unittest
import prepare_publication as publication


class PublicationTest(unittest.TestCase):
    def test_scanner_rejects_credentials_identity_paths_and_binary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            secret = 'local-' + 'secret-value'
            key = 'sk-' + 'x' * 32
            home = chr(47).join(['', 'Users', 'fixture', 'project'])
            identity = 'cmv' + 'z' * 20
            email = 'person' + '@' + 'private.invalid'
            (root / 'source.txt').write_text('\n'.join([secret, key, home, identity, email]))
            (root / 'binary.bin').write_bytes(bytes([255, 254, 253]))
            kinds = {row['kind'] for row in publication.scan(root, {secret})}
            self.assertEqual(kinds, {'exact_local_credential', 'credential_format',
                'personal_home_path', 'historical_langfuse_id', 'email_address', 'unreviewed_binary'})

    def test_scanner_allows_fixture_addresses(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'fixture.txt').write_text('test' + '@' + 'example.com')
            self.assertEqual(publication.scan(root, set()), [])

    def test_sources_exclude_local_config_reports_and_generated_files(self):
        paths = [p.relative_to(publication.ROOT) for p in publication.sources()]
        for path in paths:
            self.assertNotIn('results', path.parts)
            self.assertNotIn('__pycache__', path.parts)
            self.assertNotEqual(path.name, 'config.yaml')
            self.assertNotIn(path.suffix, {'.class', '.pyc', '.log'})
        self.assertNotIn(Path('evals/TRANSFER_V4_RESULTS.md'), paths)
        self.assertNotIn(Path('evals/finish_transfer_v4.py'), paths)
        self.assertIn(Path('evals/transfer_v4.py'), paths)


if __name__ == '__main__':
    unittest.main()
