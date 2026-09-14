import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('direct_luna', Path(__file__).with_name('qualify-document-direct-luna.py'))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class DirectScopeTest(unittest.TestCase):
    def test_code_tools_are_closed_and_bounded(self):
        tools = module.tool_specs(dict(toolName='submit_document_code_assessment', schema={'type': 'object'}))
        self.assertNotIn('bash', tools)
        self.assertTrue(tools['submit_document_code_assessment']['annotations']['readOnlyHint'])
        schema = tools['read_requirement_code']['inputSchema']
        valid = dict(runId='azx0-analysis', path='ReceiptFlow.java', blobSha='abc', startLine=1, limit=200)
        module.base.validate_shape(valid, schema)
        for bad in [dict(valid, limit=201), dict(valid, startLine=0), dict(valid, execute=True), dict(valid, limit=True)]:
            with self.assertRaises(ValueError): module.base.validate_shape(bad, schema)

    def test_source_resource_requires_one_bounded_uri(self):
        schema = module.tool_specs(dict(toolName='submit', schema={}))['read_document_resource']['inputSchema']
        module.base.validate_shape(dict(uri='loopper-document://review/azx0-analysis/index/0'), schema)
        with self.assertRaises(ValueError): module.base.validate_shape(dict(uri='x'*2049), schema)

if __name__ == '__main__': unittest.main()
