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

    def test_behavior_review_binds_compiled_result_and_frozen_book(self):
        import hashlib
        corpus={'cases':[{'id':'case','family':'family','split':'heldout','expectedClass':'RESOLVABLE','semanticChecklist':[{'id':'G1'}]}]}
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/'run.json').write_text(json.dumps({'caseId':'case','elapsedSeconds':1,'fixtureUnchanged':True,'sourceReviewAccepted':True}))
            (root/'attempts.json').write_text(json.dumps([{'candidateSha256':'candidate','compiledResultJson':'compiled','response':{'outcome':'ACCEPTED'}}]))
            (root/'source-review-validation.json').write_text(json.dumps({'bookSha256':'book'}))
            review={'candidateSha256':'candidate','scopeVerdict':'SUPPORTED','checks':[{'id':'G1','verdict':'SUPPORTED','evidence':'compiled scenario'}]}
            (root/'semantic-review.json').write_text(json.dumps(review))
            self.assertFalse(report.aggregate(corpus,[root])[0]['correctWithinFour'])
            review.update(bookSha256='book',compiledResultSha256=hashlib.sha256(b'compiled').hexdigest())
            (root/'semantic-review.json').write_text(json.dumps(review))
            self.assertTrue(report.aggregate(corpus,[root])[0]['correctWithinFour'])

    def test_preflight_decisions_are_classification_not_design_success(self):
        corpus={'cases':[{'id':'case','family':'family','split':'heldout','expectedClass':'BUSINESS_DECISION','semanticChecklist':[]}]}
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/'run.json').write_text(json.dumps({'caseId':'case','elapsedSeconds':0,'fixtureUnchanged':True,'reasonCode':'PACKAGE_GAP_BUSINESS_DECISION','actualModelRequests':0,'requestCountAvailable':True}))
            rows=report.aggregate(corpus,[root])
            self.assertTrue(rows[0]['classificationCorrect'])
            self.assertFalse(rows[0]['correctWithinFour'])
            self.assertEqual(report.metrics(rows)['blockedClassificationCorrect'],1)
            self.assertEqual(report.metrics(rows)['actualModelRequests'],0)

if __name__=='__main__':unittest.main()
