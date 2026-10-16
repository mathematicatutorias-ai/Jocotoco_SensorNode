import importlib.util
from pathlib import Path
import unittest
from urllib.error import HTTPError
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('sensornode', Path(__file__).resolve().parents[1] / 'client' / 'sensornode.py')
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class ClientTests(unittest.TestCase):
    def test_safe_name(self):
        self.assertEqual(module.safe_name('photo_20261016_120000.jpg'), 'photo_20261016_120000.jpg')
        for name in ('../test', '/etc/passwd', '..\\foo', '', 'hello world'):
            with self.assertRaises(SystemExit): module.safe_name(name)

    def test_api_status(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *_): return None
            def read(self): return b'{"model":"SM-J106M"}'
        with patch.object(module, 'urlopen', return_value=Response()) as call:
            result = module.call('http://127.0.0.1:8765', '/v1/status', 'exampletoken')
            self.assertEqual(result['model'], 'SM-J106M')
            req = call.call_args.args[0]
            self.assertEqual(req.headers['X-sensornode-token'], 'exampletoken')

    def test_download_no_overwrite(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            (output / 'a.jpg').write_bytes(b'original')
            with patch.object(module, 'call', return_value=b'new'):
                with self.assertRaises(SystemExit): module.download('http://localhost', 'token', 'a.jpg', output)
            self.assertEqual((output / 'a.jpg').read_bytes(), b'original')


if __name__ == '__main__': unittest.main()
