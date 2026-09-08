#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('report',Path(__file__).with_name('report-package-design-luna.py'))
report=importlib.util.module_from_spec(spec);spec.loader.exec_module(report)

class SemanticGateTest(unittest.TestCase):
    def test_compiler_acceptance_missing_check_or_changed_candidate_never_counts_as_correct(self):
        corpus={'cases':[{'id':'case','family':'family','split':'heldout','expectedClass':'RESOLVABLE',
                          'semanticChecklist':[{'id':'G1','assertion':'branch one'},{'id':'G2','assertion':'branch two'}]}]}
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/'run.json').write_text(json.dumps({'caseId':'case','elapsedSeconds':1,'fixtureUnchanged':True}))
            (root/'attempts.json').write_text(json.dumps([{'candidateSha256':'frozen','response':{'outcome':'ACCEPTED'}}]))
            self.assertFalse(report.aggregate(corpus,[root])[0]['correctWithinFour'])
            review={'candidateSha256':'frozen','scopeVerdict':'SUPPORTED','checks':[{'id':'G1','verdict':'SUPPORTED','evidence':'SC1'}]}
            (root/'semantic-review.json').write_text(json.dumps(review))
            self.assertFalse(report.aggregate(corpus,[root])[0]['correctWithinFour'])
            review['checks'].append({'id':'G2','verdict':'SUPPORTED','evidence':'SC2'})
            (root/'semantic-review.json').write_text(json.dumps(review))
            self.assertTrue(report.aggregate(corpus,[root])[0]['correctWithinFour'])
            review['candidateSha256']='different'
            (root/'semantic-review.json').write_text(json.dumps(review))
            self.assertFalse(report.aggregate(corpus,[root])[0]['correctWithinFour'])

if __name__=='__main__':unittest.main()
