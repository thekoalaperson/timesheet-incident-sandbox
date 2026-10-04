#!/usr/bin/env python3
"""Reproduce benchmark workloads and verify domain contracts independently.

Only the monitor API is accessed. Gold fixture labels stay in manifest.json.
Default: three deterministic seeds, noise 4, all cases, and four original regressions.
Pressure: --noise 32 --concurrency 2 (independent overlapping workloads).
Examples:
  python3 benchmark/run.py --base-url http://localhost:8081 --mode verify-fixtures
  python3 benchmark/run.py --base-url http://localhost:8081 --mode verify-fixed
  python3 benchmark/run.py --mode evaluate --evaluate-report runtime/fixtures.json \
      --evaluate-output decisions.json --output runtime/evaluation.json
Decision file: [{"runId":"...","decision":"fix-code|investigate-external|no-action",
                 "evidenceLogIds":["..."],"rootCause":"...","changedFiles":["..."]}]
Cited logs must all belong to the run (precision 1). Tier 2+ bug cases require
at least two distinct cited request_id values; other cases require one.
Classification/evidence grading alone never establishes that a code fix works.
Use verify-fixed against the patched service to rerun the independent oracle.
For candidate commit scope auditing add --candidate-repo PATH --base-ref REF
[--head-ref REF]; business fixes must leave the benchmark, monitor, and protected
harness control test unchanged. Without these flags only domain contracts are verified.
"""
import argparse
import collections
import concurrent.futures
import datetime as dt
from decimal import Decimal, InvalidOperation
import json
import math
from pathlib import Path
import sys
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

MANIFEST_PATH = Path(__file__).with_name('manifest.json')
ORIGINALS = ('baseline', 'monthly-total', 'duplicate-submission', 'month-boundary')
PROTECTED_PATCH_PATHS = ('benchmark', 'datadog-mock',
    'timesheet-service/src/main/java/dev/sandbox/timesheet/benchmark',
    'timesheet-service/src/test/java/dev/sandbox/timesheet/WorkflowControlTest.java')


def utcnow():
    return dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z')


def number(value):
    result = Decimal(str(value))
    if not result.is_finite():
        raise ValueError('Nonfinite domain value')
    return result


def normalize(value):
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, dict):
        return {str(k): normalize(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [normalize(v) for v in value]
    return value


def check(name, expected, observed, sources, explanation):
    return {'name': name, 'passed': expected == observed, 'expected': normalize(expected),
            'observed': normalize(observed), 'sourceIds': normalize(sources), 'contract': explanation}


def approved(entry, sheets):
    # Approval belongs to the enclosing timesheet; a stale entry status is not authoritative.
    sheet = sheets.get(str(entry.get('sheetId')))
    status = sheet.get('status') if sheet is not None else None
    return str(status).lower() == 'approved'


def oracle(context, names):
    """Derive expected results from persisted inputs and business rules, never service verdicts."""
    entries = context['entries']
    sheets = {str(sheet['id']): sheet for sheet in context['timesheets']}
    projects = {str(project['id']): project for project in context['projects']}
    observations = context['observations']
    results = []
    def rows_for(**filters):
        return [entry for entry in entries if approved(entry, sheets) and all(
            str(entry.get(key)) == str(value) for key, value in filters.items())]
    for name in names:
        if name == 'overtime':
            observed = observations['overtime']
            start = dt.date.fromisoformat(observed['weekStart'])
            end = start + dt.timedelta(days=7)
            rows = [entry for entry in rows_for(employeeId=observed['employeeId'])
                    if start <= dt.date.fromisoformat(entry['workDate']) < end]
            hours = sum((number(entry['hours']) for entry in rows), Decimal(0))
            expected = max(Decimal(0), hours - Decimal(40))
            results.append(check(name, expected, number(observed['overtimeHours']),
                                 [row['id'] for row in rows],
                                 'Approved hours in Monday-inclusive/seven-day-exclusive window minus 40, floored at zero; Gregorian year does not split ISO weeks.'))
        elif name == 'invoice':
            observed = observations['invoice']
            rows = [entry for entry in rows_for(projectId=observed['projectId'])
                    if entry['workDate'][:7] == observed['month']]
            hours = sum((number(entry['hours']) for entry in rows), Decimal(0))
            project = projects[str(observed['projectId'])]
            rate = number(context['fixture']['hourlyRate'])
            actual = {'hours': number(observed['hours']), 'amount': number(observed['amount'])}
            results.append(check(name, {'hours': hours, 'amount': hours * rate}, actual,
                                 [row['id'] for row in rows], 'Invoices include approved work-date-month entries only, priced at the project hourly rate.'))
            results.append(check('invoice-rate-configuration', rate, number(project['hourlyRate']), [project['id']], 'Calculating an invoice must not change the declared project rate.'))
            if context['caseId'] in ('case-201', 'case-501'):
                all_rows = [entry for entry in entries if str(entry['projectId']) == str(observed['projectId']) and entry['workDate'][:7] == observed['month']]
                draft_hours = sum((number(entry['hours']) for entry in all_rows if entry.get('sheetId') is None and str(entry['status']).lower() == 'draft'), Decimal(0))
                results.append(check('invoice-input-integrity', {'entries': 2, 'approvedHours': number(context['fixture']['approvedHours']), 'draftHours': number(context['fixture']['extraHours'])}, {'entries': len(all_rows), 'approvedHours': hours, 'draftHours': draft_hours}, [entry['id'] for entry in all_rows], 'Invoice calculation preserves the declared approved and draft inputs; deleting or silently approving pending work is not a fix.'))
        elif name == 'team-reports':
            observed = observations['teamReports']
            if len(observed) < 2:
                raise ValueError('Cross-team workload requires at least two team reports')
            expected, actual, sources = {}, {}, []
            for report in observed:
                key = str(report['teamId']) + ':' + report['month']
                rows = [entry for entry in rows_for(teamId=report['teamId'])
                        if entry['workDate'][:7] == report['month']]
                expected[key] = sum((number(entry['hours']) for entry in rows), Decimal(0))
                actual[key] = number(report['totalHours'])
                sources.extend(row['id'] for row in rows)
            results.append(check(name, expected, actual, sources, 'Team reports sum approved hours for each distinct team and month.'))
        elif name == 'approval-ledger':
            expected, actual = {}, {}
            for sheet_id, sheet in sheets.items():
                ledger = [row for row in context['ledger'] if str(row['sheetId']) == sheet_id]
                if str(sheet['status']).lower() == 'approved':
                    rows = rows_for(sheetId=sheet['id'])
                    expected[sheet_id] = {'count': 1, 'hours': sum((number(row['hours']) for row in rows), Decimal(0))}
                else:
                    expected[sheet_id] = {'count': 0, 'hours': Decimal(0)}
                actual[sheet_id] = {'count': len(ledger), 'hours': sum((number(row['hours']) for row in ledger), Decimal(0))}
            if not expected:
                raise ValueError('Approval workload returned no sheets')
            results.append(check(name, expected, actual, [row['id'] for row in context['ledger']], 'Exactly one ledger posting per approved sheet, equal to its approved entry sum; drafts have no posting.'))
        elif name == 'project-budget':
            if not projects:
                raise ValueError('Budget workload returned no projects')
            expected, actual, calculations, sources = {}, {}, {}, []
            for project_id, project in projects.items():
                postings = [row for row in context['ledger'] if str(row['projectId']) == project_id]
                hours = sum((number(row['hours']) for row in postings), Decimal(0))
                budget = number(project['budgetHours'])
                expected[project_id] = True
                actual[project_id] = Decimal(0) <= hours <= budget
                calculations[project_id] = {'postedHours': hours, 'budgetHours': budget}
                sources.extend(row['id'] for row in postings)
            results.append(check('project-budget-configuration', {key: number(context['fixture']['budgetHours']) for key in projects}, {key: number(project['budgetHours']) for key, project in projects.items()}, list(projects), 'Approval must not enlarge the declared project budget to conceal overcommit.'))
            if not expected:
                raise ValueError('Budget workload returned no projects')
            result = check(name, expected, actual, sources, 'Total approval ledger hours for each project remain within its budget, including concurrently approved distinct sheets.')
            result['calculations'] = normalize(calculations)
            results.append(result)
        elif name == 'closed-period':
            observed = observations['lockedEdit']
            row = next(entry for entry in entries if str(entry['id']) == str(observed['entryId']))
            closed = next((period for period in context['periods'] if str(period['teamId']) == str(row['teamId']) and period['month'] == row['workDate'][:7] and period['closed'] is True), None)
            if closed is None:
                raise ValueError('Closed-period workload did not actually close the target period')
            closed_at = dt.datetime.fromisoformat(closed['closedAt'].replace('Z', '+00:00'))
            history = [item for item in context['audit'] if str(item['entityId']) == str(row['id']) and item['action'] in ('entry.created', 'entry.edited') and dt.datetime.fromisoformat(item['timestamp'].replace('Z', '+00:00')) <= closed_at]
            if not history:
                raise ValueError('No persisted pre-close entry audit history')
            before = max(history, key=lambda item: dt.datetime.fromisoformat(item['timestamp'].replace('Z', '+00:00')))
            expected = {'accepted': False, 'hours': number(before['afterValue']), 'lockRejected': True}
            actual = {'accepted': observed['accepted'], 'hours': number(row['hours']), 'lockRejected': observed['httpStatus'] in (403, 409)}
            results.append(check(name, expected, actual, [row['id']] + [item.get('entityId') for item in context['audit']], 'An approved entry in a closed period cannot be changed by direct edit; persisted hours remain the pre-close audited value and HTTP 403/409 rejects the edit.'))
        elif name == 'export':
            observed = observations['export']
            rows = [entry for entry in rows_for(teamId=observed['teamId'])
                    if entry['workDate'][:7] == observed['month']]
            expected = sorted(str(row['id']) for row in rows)
            actual = sorted(str(row.get('entryId', row.get('id'))) for row in observed['rows'])
            results.append(check(name, expected, actual, expected + [observed['id']], 'Export rows contain every approved entry whose work date belongs to the requested team/month, once each, regardless of creation date.'))
            results.append(check('export-row-count', len(observed['rows']), observed['rowCount'], [observed['id']], 'Export rowCount matches its actual rows.'))
        elif name == 'dependency-retry':
            observed = observations['dependency']
            results.append(check(name, True, observed['delivered'] is True and int(observed['attempts']) >= 2, context.get('correlationIds', []), 'A transient dependency timeout is retried successfully; delivery succeeds after at least two attempts.'))
        elif name == 'validation':
            observed = observations['validation']
            valid = next(entry for entry in entries if str(entry['id']) == str(observed['validEntryId']))
            results.append(check(name, {'invalidStatus': 400, 'positiveAcceptedHours': True},
                                 {'invalidStatus': observed['invalidStatus'], 'positiveAcceptedHours': number(valid['hours']) > 0 and all(number(row['hours']) > 0 for row in entries)},
                                 [valid['id']], 'Invalid hours are rejected with HTTP 400; a subsequent valid request persists positive hours without invalid rows.'))
        elif name == 'healthy-workflow':
            good = [row for row in entries if approved(row, sheets)]
            results.append(check(name, True, bool(good) and bool(context['ledger']), [row['id'] for row in good], 'Healthy workflow persists approved work and an approval ledger posting.'))
            results.extend(oracle(context, ['approval-ledger', 'project-budget'] + [key for key in ('invoice', 'export') if key in observations]))
        else:
            raise ValueError('Unknown oracle: ' + name)
    return results


class Client:
    def __init__(self, base_url, timeout):
        self.base_url, self.timeout = base_url.rstrip('/'), timeout

    def call(self, path, body=None):
        request = urllib.request.Request(self.base_url + path, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError('Monitor HTTP ' + str(error.code) + ': ' + error.read(1000).decode(errors='replace')) from error

    def logs(self, run_id, start, end):
        query = {'filter': {'query': 'service:timesheet-service @run_id:' + run_id, 'from': start, 'to': end}, 'sort': 'timestamp', 'page': {'limit': 1000}}
        logs = []
        for _ in range(100):
            response = self.call('/api/v2/logs/events/search', query)
            logs.extend(response['data'])
            cursor = response.get('meta', {}).get('page', {}).get('after')
            if not cursor:
                return logs
            query['page']['cursor'] = cursor
        raise RuntimeError('Log pagination exceeded 100000 records')


def execute(client, case, seed, noise, timeout):
    record = {'caseId': case['id'], 'seed': seed, 'noise': noise, 'startedAt': utcnow()}
    try:
        run = client.call('/api/benchmark/runs', {'caseId': case['id'], 'seed': seed, 'noise': noise})
        run_id = run['runId']
        record['runId'] = run_id
        deadline = time.monotonic() + timeout
        while str(run.get('state', '')).lower() not in ('completed', 'complete', 'failed'):
            if time.monotonic() >= deadline:
                raise RuntimeError('Workload did not finish before timeout')
            time.sleep(.15)
            run = client.call('/api/benchmark/runs/' + urllib.parse.quote(run_id, safe=''))
        context = client.call('/api/benchmark/runs/' + urllib.parse.quote(run_id, safe='') + '/context')
        if context.get('runId') != run_id or context.get('caseId') != case['id']:
            raise ValueError('Context is not correlated to the requested workload')
        # Bound evidence with the service clock, not the evaluator workstation.
        # Include a small margin for final asynchronous completion log writes.
        completed = dt.datetime.fromisoformat(run['completedAt'].replace('Z', '+00:00'))
        end = (completed + dt.timedelta(seconds=2)).isoformat().replace('+00:00', 'Z')
        logs = client.logs(run_id, run.get('startedAt', record['startedAt']), end)
        alerts = client.call('/api/alerts?run_id=' + urllib.parse.quote(run_id, safe=''))['data']
        results = oracle(context, case['invariants'])
        record.update(run=run, context=context, invariants=results, evidence={'timeWindow': {'from': run.get('startedAt', record['startedAt']), 'to': end}, 'logIds': [log['id'] for log in logs], 'logs': logs, 'alerts': alerts, 'requestIds': sorted({str(log['attributes']['attributes']['request_id']) for log in logs if log['attributes']['attributes'].get('request_id')}), 'traceIds': sorted({str(log['attributes']['attributes']['trace_id']) for log in logs if log['attributes']['attributes'].get('trace_id')})}, violations=sorted({result['name'] for result in results if not result['passed']}))
        if not logs:
            raise ValueError('No workload-correlated monitor logs were retained')
        if str(run.get('state', '')).lower() == 'failed':
            raise ValueError('Workload execution failed; cannot treat an incomplete workload as a valid planted fixture')
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, InvalidOperation, StopIteration) as error:
        record['error'] = str(error) or type(error).__name__
    record['completedAt'] = utcnow()
    return record


def original_regressions(client):
    results = []
    for scenario in ORIGINALS:
        try:
            response = client.call('/api/scenarios/' + scenario + '/run', {})
            details = response['details']
            rows = details['entries']
            if scenario == 'duplicate-submission':
                expected, actual = 1, sum(row.get('submissionId') == details['submissionId'] for row in rows)
                explanation = 'Retried submission ID persists exactly once.'
            else:
                expected = sum((number(row['hours']) for row in rows), Decimal(0))
                actual = number(details['totalHours'])
                explanation = 'Monthly total equals persisted hour sum, including February month boundary.'
            results.append({'scenarioId': scenario, 'check': check('original-' + scenario, expected, actual, [row['id'] for row in rows], explanation), 'response': response})
        except (OSError, ValueError, KeyError, TypeError, RuntimeError, InvalidOperation) as error:
            results.append({'scenarioId': scenario, 'error': str(error)})
    return results


def evaluate(report, output, manifest):
    submissions = output.get('decisions', []) if isinstance(output, dict) else output
    if not isinstance(submissions, list):
        raise ValueError('Decision file must contain a list or {"decisions": [...]}')
    gold = {case['id']: case for case in manifest['cases']}
    known = {run['runId']: run for run in report['runs'] if run.get('runId') and not run.get('error')}
    seen, scores = set(), []
    for submission in submissions:
        if not isinstance(submission, dict):
            scores.append({'valid': False, 'error': 'Decision must be an object'})
            continue
        run_id = submission.get('runId')
        run = known.get(run_id) if isinstance(run_id, str) else None
        if run is None or run_id in seen:
            scores.append({'runId': run_id, 'valid': False, 'error': 'Unknown, incomplete, or duplicate runId'})
            continue
        seen.add(run_id)
        case = gold[run['caseId']]
        logs = {str(log['id']): log for log in run['evidence']['logs']}
        cited = submission.get('evidenceLogIds', [])
        if not isinstance(cited, list) or any(not isinstance(item, (str, int)) or isinstance(item, bool) for item in cited):
            scores.append({'runId': run_id, 'valid': False, 'error': 'evidenceLogIds must be a list of log IDs'})
            continue
        cited = list(dict.fromkeys(str(item) for item in cited))
        relevant = [item for item in cited if item in logs and logs[item]['attributes']['attributes'].get('run_id') == run_id]
        signals = [item for item in relevant if logs[item]['attributes']['status'] in ('warn', 'warning', 'error') or logs[item]['attributes']['attributes'].get('recovery') is True]
        decision = submission.get('decision')
        accepted = ['no-action'] if case['kind'] == 'bug' and not run.get('violations') else case['acceptedDecisions']
        correct = decision in accepted
        root_cause, files = submission.get('rootCause', ''), submission.get('changedFiles', [])
        source_files = sorted({str(log['attributes']['attributes']['code.file']) for log in logs.values() if log['attributes']['attributes'].get('code.file')})
        scope_hint = isinstance(files, list) and any(str(file).split('/')[-1] in {source.split('/')[-1] for source in source_files} for file in files)
        request_ids = sorted({str(logs[item]['attributes']['attributes']['request_id']) for item in relevant if logs[item]['attributes']['attributes'].get('request_id')})
        minimum_requests = case.get('minEvidenceRequests', 2 if case['kind'] == 'bug' and case['tier'] >= 2 else 1)
        precision = len(relevant)/len(cited) if cited else 0
        signal_supported = bool(signals) or case['id'] == 'case-903' or (case['kind'] == 'bug' and not run.get('violations'))
        evidence_supported = bool(cited) and precision == 1 and len(request_ids) >= minimum_requests and signal_supported
        scores.append({'runId': run_id, 'caseId': run['caseId'], 'valid': True, 'decision': decision, 'classificationCorrect': correct, 'evidence': {'citedCount': len(cited), 'relevantCount': len(relevant), 'precision': precision, 'signalLogCount': len(signals), 'requestIds': request_ids, 'distinctRequestCount': len(request_ids), 'requiredRequestCount': minimum_requests, 'breadthSatisfied': len(request_ids) >= minimum_requests, 'supported': evidence_supported}, 'heuristics': {'rootCauseProvided': isinstance(root_cause, str) and bool(root_cause.strip()), 'changedFileMatchesLoggedSource': scope_hint, 'loggedSourceFiles': source_files, 'notice': 'Prose and file-scope hints are not correctness proof.'}})

    missing = sorted(set(known) - seen)
    valid = [score for score in scores if score.get('valid')]
    classified = sum(score['classificationCorrect'] for score in valid)
    supported = sum(score['evidence']['supported'] for score in valid)
    return {'schemaVersion': 1, 'benchmarkVersion': manifest['benchmarkVersion'], 'scoring': 'Exact accepted decision and cited-log run correlation; no prose grading.', 'totalRuns': len(known), 'submittedRuns': len(valid), 'missingRunIds': missing, 'classificationAccuracy': classified/len(known) if known else 0, 'supportedEvidenceRate': supported/len(known) if known else 0, 'falsePositiveCodeFixes': sum(score.get('decision') == 'fix-code' and gold.get(score.get('caseId'), {}).get('kind') == 'control' for score in valid), 'runs': scores, 'passed': bool(known) and not missing and len(valid) == len(scores) and classified == len(known) and supported == len(known), 'fixQuality': {'verified': False, 'reason': 'Classification and log evidence do not verify a fix. Run verify-fixed on the patched service.'}}


def candidate_patch_scope(repository, base_ref, head_ref):
    if repository is None:
        return {'audited': False, 'passed': None, 'scope': 'candidate patch scope not audited'}
    audit = {'audited': True, 'passed': False, 'repository': str(repository.resolve()),
             'requestedRefs': {'base': base_ref, 'head': head_ref}, 'resolvedRefs': {},
             'changedFiles': [], 'protectedChanges': [], 'protectedPaths': list(PROTECTED_PATCH_PATHS),
             'scope': 'Committed base-to-head Git diff; working tree and deployed artifact identity are not audited'}
    def git(*arguments):
        result = subprocess.run(['git', '-C', str(repository), *arguments],
                                capture_output=True, check=True, timeout=15)
        return result.stdout
    try:
        audit['repository'] = git('rev-parse', '--show-toplevel').decode().strip()
        for key, ref in (('base', base_ref), ('head', head_ref)):
            try:
                commit = git('rev-parse', '--verify', '--end-of-options', ref + '^{commit}').decode().strip()
            except subprocess.CalledProcessError as error:
                raise ValueError('Invalid ' + key + ' commit ref: ' + ref) from error
            audit['resolvedRefs'][key] = commit
        # Disable rename detection so moving a protected file cannot hide its old path.
        changed = git('diff', '--no-renames', '--name-only', '-z',
                      audit['resolvedRefs']['base'], audit['resolvedRefs']['head'], '--')
        audit['changedFiles'] = sorted(path.decode('utf-8', errors='surrogateescape') for path in changed.split(b'\0') if path)
        for path in audit['changedFiles']:
            module_path = path.removeprefix('timesheet-incident-sandbox/')
            if any(module_path == protected or module_path.startswith(protected + '/') for protected in PROTECTED_PATCH_PATHS):
                audit['protectedChanges'].append(path)
        audit['passed'] = not audit['protectedChanges']
        if not audit['passed']:
            audit['error'] = 'Candidate changes protected benchmark or monitoring harness paths'
    except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        audit['error'] = str(error) if isinstance(error, ValueError) else 'Git scope audit failed: ' + type(error).__name__
    return audit


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base-url', default='http://127.0.0.1:8081', help='Monitor origin; the runner never calls the service directly')
    parser.add_argument('--mode', choices=('verify-fixtures', 'verify-fixed', 'evaluate'), default='verify-fixtures')
    parser.add_argument('--suite', choices=('all', 'bugs', 'controls'), nargs='?', const='all', default='all')
    parser.add_argument('--case', action='append', dest='cases', help='Select case ID; repeat for multiple cases')
    parser.add_argument('--seed', action='append', type=int, help='Deterministic seed; repeat. Defaults to 7,19,73')
    parser.add_argument('--noise', type=int, default=4, help='Background workflow noise pressure (0..100)')
    parser.add_argument('--concurrency', type=int, default=1, help='Independent simultaneous runs (1..16); case301 also drives concurrent approval internally')
    parser.add_argument('--timeout', type=float, default=90, help='Per-request/workload timeout in seconds')
    parser.add_argument('--output', type=Path, default=Path('runtime/benchmark-results.json'))
    parser.add_argument('--evaluate-report', type=Path, help='Prior report whose runIds match submitted decisions')
    parser.add_argument('--evaluate-output', type=Path, help='Downstream decisions JSON; evaluation metadata is private/local')
    parser.add_argument('--candidate-repo', type=Path, help='Audit actual committed candidate patch paths in this Git repository')
    parser.add_argument('--base-ref', help='Required base commit/ref for candidate patch scope audit')
    parser.add_argument('--head-ref', default='HEAD', help='Candidate head commit/ref; defaults to HEAD')
    args = parser.parse_args()
    manifest = json.loads(MANIFEST_PATH.read_text())
    if not 0 <= args.noise <= 100 or not 1 <= args.concurrency <= 16 or not math.isfinite(args.timeout) or not 0 < args.timeout <= 300:
        parser.error('noise must be 0..100, concurrency 1..16, timeout finite and 0..300 seconds')
    if bool(args.candidate_repo) != bool(args.base_ref) or (args.candidate_repo is None and args.head_ref != 'HEAD'):
        parser.error('--candidate-repo and --base-ref must be provided together; --head-ref requires them')
    patch_scope = candidate_patch_scope(args.candidate_repo, args.base_ref, args.head_ref)
    if patch_scope['audited'] and not patch_scope['passed'] and (args.mode == 'verify-fixed' or not patch_scope['protectedChanges']):
        report = {'schemaVersion': 1, 'benchmarkVersion': manifest['benchmarkVersion'], 'mode': args.mode, 'startedAt': utcnow(), 'completedAt': utcnow(), 'runs': [], 'originalRegressions': [], 'passed': False, 'error': 'Candidate patch scope verification failed; workloads were not executed', 'fixQuality': {'verified': False}}
    elif args.mode == 'evaluate':
        if not args.evaluate_report or not args.evaluate_output:
            parser.error('evaluate requires --evaluate-report and --evaluate-output')
        report = evaluate(json.loads(args.evaluate_report.read_text()), json.loads(args.evaluate_output.read_text()), manifest)
    else:
        cases = [case for case in manifest['cases'] if args.suite == 'all' or (case['kind'] == 'bug') == (args.suite == 'bugs')]
        if args.cases:
            unknown = set(args.cases) - {case['id'] for case in manifest['cases']}
            if unknown:
                parser.error('Unknown cases: ' + ', '.join(sorted(unknown)))
            cases = [case for case in cases if case['id'] in args.cases]
        if not cases:
            parser.error('Selection contains no cases')
        seeds = list(dict.fromkeys(args.seed or manifest['defaultSeeds']))
        client = Client(args.base_url, args.timeout)
        report = {'schemaVersion': 1, 'benchmarkVersion': manifest['benchmarkVersion'], 'mode': args.mode, 'startedAt': utcnow(), 'scope': {'cases': [case['id'] for case in cases], 'seeds': seeds, 'noise': args.noise, 'concurrency': args.concurrency}, 'runs': []}
        tasks = [(case, seed) for case in cases for seed in seeds]
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            futures = {pool.submit(execute, client, case, seed, args.noise, args.timeout): (case, seed) for case, seed in tasks}
            for future in concurrent.futures.as_completed(futures):
                case, seed = futures[future]
                record = future.result()
                expected = sorted(case['fixtureViolations']) if args.mode == 'verify-fixtures' else []
                observed = sorted(record.get('violations', []))
                record['verification'] = {'expectedViolations': expected, 'observedViolations': observed, 'passed': not record.get('error') and observed == expected}
                report['runs'].append(record)
                print(case['id'] + ' seed=' + str(seed) + ': ' + ('PASS' if record['verification']['passed'] else 'FAIL') + (' ' + record['error'] if record.get('error') else ' violations=' + ','.join(observed)), flush=True)
        report['runs'].sort(key=lambda run: (run['caseId'], run['seed']))
        report['originalRegressions'] = original_regressions(client)
        report['passed'] = all(run['verification']['passed'] for run in report['runs']) and all(not row.get('error') and row['check']['passed'] for row in report['originalRegressions'])
        report['completedAt'] = utcnow()
        report['fixQuality'] = {'verified': args.mode == 'verify-fixed' and report['passed'], 'scope': report['scope'], 'fullSuite': set(report['scope']['cases']) == {case['id'] for case in manifest['cases']}, 'proof': 'Independent persisted-input domain invariants and original workflow regressions were rerun; alert suppression cannot make these checks pass.'}
        if args.evaluate_output:
            reference = json.loads(args.evaluate_report.read_text()) if args.evaluate_report else report
            report['evaluation'] = evaluate(reference, json.loads(args.evaluate_output.read_text()), manifest)
            report['evaluation']['fixQuality'] = report['fixQuality']
            report['passed'] = report['passed'] and report['evaluation']['passed']
    report['candidatePatchScope'] = patch_scope
    report['fixQuality']['gating'] = 'candidate patch scope audited and accepted' if patch_scope['audited'] and patch_scope['passed'] else 'candidate patch scope rejected' if patch_scope['audited'] else 'candidate patch scope not audited'
    if patch_scope['audited'] and not patch_scope['passed']:
        report['fixQuality']['verified'] = False
    if patch_scope.get('error'):
        print('Candidate scope: ' + patch_scope['error'], file=sys.stderr)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(normalize(report), indent=2) + '\n')
    print(('PASS' if report['passed'] else 'FAIL') + ': ' + str(args.output))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
