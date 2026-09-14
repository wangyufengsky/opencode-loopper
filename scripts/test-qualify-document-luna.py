import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('document_luna', Path(__file__).with_name('qualify-document-luna.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ClosedSchemaTest(unittest.TestCase):
    def test_source_read_schema_rejects_extra_fields_wrong_types_and_unbounded_ranges(self):
        schema = next(t['inputSchema'] for t in module.source_tools() if t['name'] == 'list_document_sections')
        valid = dict(runId='azx0-extraction', fileId='azx0-document', after=-1, limit=15)
        module.validate_shape(valid, schema)
        for invalid in [dict(valid, path='/private'), dict(valid, after=True), dict(valid, limit=101), dict(valid, fileId=None)]:
            with self.assertRaises(ValueError):
                module.validate_shape(invalid, schema)

    def test_nullable_and_enumerated_candidates_remain_closed(self):
        module.validate_shape(None, {'type': ['string', 'null']})
        with self.assertRaises(ValueError):
            module.validate_shape('PASS', {'type': 'string', 'enum': ['ACCEPTED', 'REJECTED']})
        with self.assertRaises(ValueError):
            module.validate_shape(['a', 'b'], {'type': 'array', 'maxItems': 1, 'items': {'type': 'string'}})


if __name__ == '__main__':
    unittest.main()
