import unittest
import hashlib
import json
from pathlib import Path
import tempfile
from unittest.mock import patch
import cli


class PublicCliTest(unittest.TestCase):
    def test_calibration_rejects_stale_and_incomplete_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evidence = root / 'results/transfer-v4-calibration'
            evidence.mkdir(parents=True)
            source = root / 'fixture.java'
            source.write_text('original')
            metadata = evidence / 'verified.json'
            metadata.write_text(json.dumps(dict(ready=False)))
            with patch.object(cli, 'ROOT', root), patch.object(cli, 'EVALS', root):
                self.assertFalse(cli.calibration_status()[0])
                metadata.write_text(json.dumps(dict(ready=True, hashes={
                    'fixture.java': hashlib.sha256(source.read_bytes()).hexdigest()})))
                self.assertTrue(cli.calibration_status()[0])
                source.write_text('changed')
                self.assertFalse(cli.calibration_status()[0])

    def test_build_stops_after_failed_production_build(self):
        with patch.object(cli, 'invoke', return_value=7) as execute:
            self.assertEqual(cli.main(['build']), 7)
            execute.assert_called_once_with('build.py')

    def test_spool_inspection_does_not_enable_upload(self):
        with patch.object(cli, 'invoke', return_value=0) as execute, patch.object(cli.Path, 'is_file', return_value=True):
            self.assertEqual(cli.main(['spans', '.']), 0)
            self.assertEqual(execute.call_args.args[0], 'upload_spool_v3.py')
            self.assertNotIn('--upload', execute.call_args.args)
            self.assertNotIn('--verify', execute.call_args.args)

    def test_upload_and_verify_are_mutually_exclusive(self):
        with self.assertRaises(SystemExit), patch.object(cli, 'invoke') as execute:
            cli.main(['spans', '.', '--upload', '--verify'])
        execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
