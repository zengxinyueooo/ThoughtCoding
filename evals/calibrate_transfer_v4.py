"""Calibrate base/reference/known-bad using real Java tests, zero model calls."""
from concurrent.futures import ThreadPoolExecutor
import difflib
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from run import ROOT,seed_public_files
from run_v2 import apply_patch,PUBLIC
from run_v4 import snapshot,CALIBRATION
from command_wallclock import command
from scoring_v2 import evaluate_test_reports

NEW=['write-string-type','write-lf-budget','write-valid-utf16','write-line-width','write-control-text']
OLD=['dispatcher-failure','registry-invalid','session-latest']


def main():
    CALIBRATION.mkdir(parents=True)
    tasks={t:json.loads((ROOT/'evals/tasks'/f'{t}.json').read_text(encoding='utf-8')) for t in NEW+OLD}
    maven=(shutil.which('mvn.cmd') or shutil.which('mvn'))
    def calibrate(ident):
        task=tasks[ident];source=task['allowed_paths'][0]
        if ident in OLD:
            before=subprocess.check_output(['git','show',task['base_commit']+':'+source],cwd=ROOT).decode('utf-8')
            after=subprocess.check_output(['git','show',task['reference_commit']+':'+source],cwd=ROOT).decode('utf-8')
            patch=CALIBRATION/(ident+'-reference.patch')
            patch.write_text(''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='a/'+source,tofile='b/'+source)),encoding='utf-8')
        else:patch=ROOT/'evals/reference-patches'/f'{ident}.patch'
        results=[];expected=None
        for label in ['base','reference']+(['known-bad'] if ident in NEW else []):
            directory=CALIBRATION/(ident+'--'+label)
            snapshot(task['base_commit'],directory);seed_public_files(dict(task,public_files=PUBLIC),directory)
            if label!='base':apply_patch(task,directory,ROOT/'evals/reference-patches'/f'{ident}-bad.patch' if label=='known-bad' else patch)
            target=directory/'src/test/java/com/thoughtcoding/eval'/task['acceptance_file'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/'evals/acceptance'/task['acceptance_file'],target)
            result=command([maven,'-q','-Dtest=PublicWriteCompatibilityTest,'+task['acceptance_class'],'test'],directory,CALIBRATION/(directory.name+'.log'),600)
            reports=list((directory/'target/surefire-reports').glob('TEST-*.xml'))
            suites={ET.parse(p).getroot().get('name'):{k:int(ET.parse(p).getroot().get(k,0)) for k in ['tests','failures','errors','skipped']} for p in reports}
            assert not result['timed_out'] and all(s['errors']==s['skipped']==0 for s in suites.values()),(ident,label,'build/environment error')
            private=suites[task['acceptance_class']];public=suites['com.thoughtcoding.publicchecks.PublicWriteCompatibilityTest']
            assert private['tests']>=2 and public['tests']==2
            if label=='base':assert private['failures']>0 and public['failures']==0,(ident,label)
            elif label=='known-bad':assert public['failures']>0,(ident,label)
            else:
                assert result['exit_code']==0 and private['failures']==public['failures']==0,(ident,label)
                full=command([maven,'-q','test'],directory,CALIBRATION/(ident+'-full-reference.log'),900)
                reports=list((directory/'target/surefire-reports').glob('TEST-*.xml'))
                assert evaluate_test_reports(full,reports,minimum_tests=1)['passed'],(ident,'full reference')
                expected={ET.parse(p).getroot().get('name'):int(ET.parse(p).getroot().get('tests')) for p in reports if not ET.parse(p).getroot().get('name').startswith('com.thoughtcoding.eval.')}
            result.update(label=label,suites=suites);results.append(result)
            print(ident,label,'calibrated',flush=True)
        value=dict(task_id=ident,ready=True,results=results,expected_regression_suites=expected)
        (CALIBRATION/(ident+'.json')).write_text(json.dumps(value,indent=2),encoding='utf-8')
        return value
    with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(calibrate,NEW+OLD))
    (CALIBRATION/'expected-suites.json').write_text(json.dumps({r['task_id']:r['expected_regression_suites'] for r in results},indent=2),encoding='utf-8')
    files=[ROOT/'evals/tasks'/f'{t}.json' for t in tasks]+[ROOT/'evals/acceptance'/t['acceptance_file'] for t in tasks.values()]
    files+=list((ROOT/'evals/reference-patches').glob('write-*.patch'))+[ROOT/'evals/public_checks/PublicWriteCompatibilityTest.java',ROOT/'evals/regression_profiles/v2/ToolDispatcherTimeoutTest.java']
    (CALIBRATION/'verified.json').write_text(json.dumps(dict(ready=True,model_calls=0,tasks=NEW+OLD,hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}),indent=2),encoding='utf-8')
    print('CALIBRATION READY: 8 task contracts',flush=True)


if __name__=='__main__':main()
