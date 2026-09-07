#!/usr/bin/env python3
"""Aggregate qualification evidence. Acceptance alone never passes independent semantics."""
import argparse
import hashlib
import json
from pathlib import Path


def aggregate(corpus, roots):
    cases = {case['id']: case for case in corpus['cases']}
    rows = []
    for root in roots:
        for path in sorted(Path(root).rglob('run.json')):
            run = json.loads(path.read_text())
            if run.get('caseId') not in cases:
                continue
            case = cases[run['caseId']]
            ledger = path.with_name('attempts.json')
            attempts = json.loads(ledger.read_text()) if ledger.exists() else []
            accepted = next((i for i,a in enumerate(attempts,1) if a['response']['outcome']=='ACCEPTED'), None)
            review_path = path.with_name('semantic-review.json')
            review = json.loads(review_path.read_text()) if review_path.exists() else {}
            checks = {item['id']:item for item in review.get('checks',[])}
            expected = {item['id'] for item in case['semanticChecklist']}
            reviewed = bool(accepted) and set(checks)==expected and all(item.get('verdict') in ('SUPPORTED','MISSING','CONTRADICTED') for item in checks.values())
            candidate_hash = attempts[accepted-1]['candidateSha256'] if accepted else None
            compiled = attempts[accepted-1].get('compiledResultJson') if accepted else None
            compiled_hash = hashlib.sha256(compiled.encode()).hexdigest() if compiled else None
            source_path = path.with_name('source-review-validation.json')
            source = json.loads(source_path.read_text()) if source_path.exists() else {}
            bound = (run.get('sourceReviewAccepted') is not True or
                     bool(compiled_hash and source.get('bookSha256') and review.get('compiledResultSha256')==compiled_hash and review.get('bookSha256')==source['bookSha256']))
            correct = reviewed and bound and review.get('candidateSha256')==candidate_hash and review.get('scopeVerdict')=='SUPPORTED' and all(item.get('verdict')=='SUPPORTED' and item.get('evidence') for item in checks.values())
            response = attempts[-1]['response'] if attempts else {}
            codes = [p['code'] for p in response.get('problems',[])]
            if run.get('reasonCode'): codes.append(run['reasonCode'])
            classification = ('PROVEN_CONFLICT' if set(codes) & {'PACKAGE_GAP_PROVEN_CONFLICT','REQUIRED_MUTATION_PATH_FORBIDDEN','MUTATION_PATH_SCOPE_CONFLICT'} else
                              'BUSINESS_DECISION' if 'PACKAGE_GAP_BUSINESS_DECISION' in codes else
                              'RESOLVABLE' if accepted else 'UNCONFIRMED')
            repeated = sum(len(a['response'].get('repairProgress',{}).get('remaining',[])) for a in attempts[1:])
            introduced = sum(len(a['response'].get('repairProgress',{}).get('introduced',[])) for a in attempts[1:])
            rows.append(dict(caseId=case['id'], family=case['family'], split=case['split'], expectedClass=case['expectedClass'],
                runPath=str(path.resolve()), candidateCount=len(attempts), acceptedOrdinal=accepted,
                independentReviewComplete=reviewed, correctWithinFour=bool(correct and accepted<=4),
                correctFirst=bool(correct and accepted==1), classification=classification,
                classificationCorrect=classification==case['expectedClass'], finalCodes=codes,
                repeatedIssues=repeated, introducedIssues=introduced,
                semanticOmissions=sum(c.get('verdict') in ('MISSING','CONTRADICTED') for c in checks.values()),
                preparationTurns=run.get('semanticPreparationTurns',0), sourceReviewTurns=run.get('sourceReviewTurns',0),
                sourceReviewAccepted=run.get('sourceReviewAccepted'), elapsedSeconds=run['elapsedSeconds'],
                turnUsage=run.get('turnUsage',[]), fixtureUnchanged=run.get('fixtureUnchanged',False),
                actualModelRequests=run.get("actualModelRequests") if run.get("requestCountAvailable") else None))
    return rows


def metrics(rows):
    resolvable = [r for r in rows if r['expectedClass']=='RESOLVABLE']
    blocked = [r for r in rows if r['expectedClass']!='RESOLVABLE']
    return dict(runs=len(rows), resolvableRuns=len(resolvable),
        correctFirst=sum(r['correctFirst'] for r in resolvable),
        correctWithinFour=sum(r['correctWithinFour'] for r in resolvable),
        fourPassRate=sum(r['correctWithinFour'] for r in resolvable)/len(resolvable) if resolvable else None,
        blockedClassificationCorrect=sum(r['classificationCorrect'] for r in blocked), blockedRuns=len(blocked),
        erroneousConflictAcceptances=sum(bool(r['acceptedOrdinal']) for r in rows if r['expectedClass']=='PROVEN_CONFLICT'),
        erroneousDecisionAcceptances=sum(bool(r['acceptedOrdinal']) for r in rows if r['expectedClass']=='BUSINESS_DECISION'),
        unreviewedAcceptances=sum(bool(r['acceptedOrdinal']) and not r['independentReviewComplete'] for r in rows),
        semanticOmissions=sum(r['semanticOmissions'] for r in resolvable), acceptedResolvablePlansWithSemanticIssues=sum(bool(r['acceptedOrdinal']) and not r['correctWithinFour'] for r in resolvable), candidateSubmissions=sum(r['candidateCount'] for r in rows),
        preparationTurns=sum(r['preparationTurns'] for r in rows), sourceReviewTurns=sum(r['sourceReviewTurns'] for r in rows),
        unconfirmedSourceReviews=sum(r['sourceReviewAccepted'] is False for r in rows), repeatedIssues=sum(r['repeatedIssues'] for r in rows),
        introducedIssues=sum(r['introducedIssues'] for r in rows),
        summedCaseSeconds=round(sum(r['elapsedSeconds'] for r in rows),3),
        tokens={key:sum(u.get(key,0) for r in rows for u in r['turnUsage']) for key in ['input_tokens','cached_input_tokens','output_tokens','reasoning_output_tokens']},
        actualModelRequests=sum(r["actualModelRequests"] for r in rows) if rows and all(r["actualModelRequests"] is not None for r in rows) else None, fixtureChanges=sum(not r['fixtureUnchanged'] for r in rows))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus',default='src/test/resources/package-design-luna/corpus.json')
    parser.add_argument('--roots',nargs='+',required=True)
    parser.add_argument('--output',required=True)
    parser.add_argument('--complex-families', help='Explicit predeclared complex families; never infer success from task length')
    args=parser.parse_args()
    corpus=json.loads(Path(args.corpus).read_text()); rows=aggregate(corpus,args.roots)
    report={'corpusSha256':hashlib.sha256(Path(args.corpus).read_bytes()).hexdigest(),
            'reviewBoundary':'Independent fixed checklist; reviewed by implementation agent, not blinded external review.',
            'all':metrics(rows),'heldout':metrics([r for r in rows if r['split']=='heldout']),'cases':rows}
    if args.complex_families:
        complex_families=set(args.complex_families.split(','))
        report.update(complexFamilies=sorted(complex_families), complex=metrics([r for r in rows if r['family'] in complex_families]), simple=metrics([r for r in rows if r['family'] not in complex_families]))
    Path(args.output).write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps({k:v for k,v in report.items() if k!='cases'},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
