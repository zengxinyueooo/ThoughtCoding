"""Frozen 20 same-module transfer trials plus 6 historical regression trials."""
import argparse
from concurrent.futures import ThreadPoolExecutor,as_completed
from contextlib import contextmanager
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import statistics
import uuid
import agent_run
import public_feedback_v3 as engine
import run_v4
from runtime_v3 import bind_runtime
from expanded_eval import sanitized
from smoke import model_config
from upload_spool_v3 import read,save
from run import ROOT

NEW=['write-string-type','write-lf-budget','write-valid-utf16','write-line-width','write-control-text']
OLD=['dispatcher-failure','registry-invalid','session-latest']
ARMS=['baseline','public-tests']


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


@contextmanager
def runtime(adapter):
    with bind_runtime(adapter_path=adapter):
        previous=(agent_run.snapshot,agent_run.check,engine.check)
        agent_run.snapshot,agent_run.check,engine.check=run_v4.snapshot,run_v4.check,run_v4.check
        try:yield
        finally:agent_run.snapshot,agent_run.check,engine.check=previous


def execute(item,manifest,config,model,batch):
    result=engine.execute(item,manifest,config,model,batch)
    result.update(cohort=item['cohort'],scoring_version='test-evidence-v4-per-case-calibrated')
    directory=batch/(batch.name+'--'+result['arm'])/(result['task_id']+'-'+str(result['repetition']))
    save(directory/'result.json',result)
    return result


def summary(batch,manifest):
    groups={}
    for cohort,planned in [('new_same_module',10),('old_regression',3)]:
        groups[cohort]={}
        for arm in ARMS:
            rows=[r for r in manifest['runs'] if r['cohort']==cohort and r['arm']==arm]
            durations=[r['e2e_latency_s'] for r in rows if r.get('performance_comparable') and r.get('e2e_latency_s') is not None]
            tools=sum(r['tool_calls'] for r in rows)
            groups[cohort][arm]=dict(completed=len(rows),planned=planned,successes=sum(r['passed'] for r in rows),
                success_rate=sum(r['passed'] for r in rows)/planned,mean_e2e_s=statistics.mean(durations) if durations else None,
                latency_samples=len(durations),llm_calls=sum(r['llm_calls'] for r in rows),tool_calls=tools,
                tool_success_rate=sum(r['tool_successes'] for r in rows)/tools if tools else None,
                usage_complete_runs=sum(r['usage_complete'] for r in rows),
                per_task={t:dict(runs=sum(r['task_id']==t for r in rows),successes=sum(r['passed'] for r in rows if r['task_id']==t)) for t in (NEW if cohort=='new_same_module' else OLD)})
    value=dict(completed=len(manifest['runs']),scheduled=26,all_trials_reported=len(manifest['runs'])==26,groups=groups,
        no_cross_module_transfer_claim=True,old_regression_one_repetition_per_arm=True)
    save(batch/'summary.json',value);print(json.dumps(value),flush=True)


def main():
    p=argparse.ArgumentParser();p.add_argument('--resume',type=Path);p.add_argument('--prepare-only',action='store_true');args=p.parse_args()
    config,model=model_config();assert model['name']=='Qwen/Qwen3-Coder-30B-A3B-Instruct'
    os.environ['THOUGHTCODING_TRACING_ENABLED']='true';os.environ['THOUGHTCODING_ENVIRONMENT']='offline-transfer-v4'
    calibration=read(run_v4.CALIBRATION/'verified.json');assert calibration['ready']
    for rel,h in calibration['hashes'].items():assert sha(ROOT/rel)==h,'Calibration input changed: '+rel
    expected_suites=read(run_v4.CALIBRATION/'expected-suites.json')
    for ident in NEW+OLD:
        calibrated=read(run_v4.CALIBRATION/(ident+'.json'))
        assert calibrated['ready'] and calibrated['expected_regression_suites']==expected_suites[ident]
    if args.resume:
        batch=args.resume.resolve();manifest=read(batch/'manifest.json');assert manifest['configuration']==sanitized(config)
        assert not (batch/'INVALIDATED.json').exists()
    else:
        batch=ROOT/'evals/results'/('transfer-v4-'+uuid.uuid4().hex[:10]);batch.mkdir()
        shutil.copytree(ROOT/'target/classes',batch/'runtime/classes');shutil.copytree(ROOT/'evals/.build-v3',batch/'runtime/adapter')
        paths=dict(classes=str(batch/'runtime/classes'),adapter=str(batch/'runtime/adapter'),dependencies=(ROOT/'target/eval-classpath.txt').read_text().strip(),local_span_backup=True)
        tasks={t:read(ROOT/'evals/tasks'/f'{t}.json') for t in NEW+OLD}
        schedule=[dict(task_id=t,repetition=r,arm=a,cohort='new_same_module') for t in NEW for r in [1,2] for a in ARMS]
        schedule += [dict(task_id=t,repetition=1,arm=a,cohort='old_regression') for t in OLD for a in ARMS]
        random.Random(20261029).shuffle(schedule)
        files=list((ROOT/'src/main/java').rglob('*.java'))
        names=['transfer_v4.py','run_v4.py','prepare_transfer_v4.py','calibrate_transfer_v4.py','TRANSFER_V4_PLAN.md','public_feedback_v3.py','runtime_v3.py','command_wallclock.py','agent_run.py','run.py','run_v2.py','scoring_v2.py','expanded_eval.py','smoke.py','standard_eval.py','verification_feedback_ab.py','langfuse_metrics.py','upload_spool_v3.py','java/EvalMain.java','java/EvalTelemetryV3.java']
        files += [ROOT/'evals'/n for n in names]+[ROOT/'pom.xml',run_v4.CALIBRATION/'expected-suites.json',run_v4.CALIBRATION/'verified.json']
        files += [ROOT/r for r in calibration['hashes']]
        files=list(dict.fromkeys(files))
        for source in files:
            target=batch/'frozen-sources'/source.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
        manifest=dict(kind='prospective_transfer_v4',started_at=datetime.now(timezone.utc).isoformat(),configuration=sanitized(config),
            model=model['name'],tasks=tasks,schedule=schedule,runs=[],workers=2,seed=20261029,runtime_paths=paths,
            source_sha256={str(f.relative_to(ROOT)):sha(f) for f in files},runtime_sha256={str(f.relative_to(batch)):sha(f) for f in (batch/'runtime').rglob('*.class')},
            primary_metrics=['new_same_module success /10 per arm','old_regression success /3 per arm'],
            stopping='26 planned runs; no outcome-driven reruns',limitation='5 new requirements in seen WriteTool module; old regression tasks each once per arm, not a noninferiority proof')
        for arm in ARMS:(batch/(batch.name+'--'+arm)).mkdir()
        save(batch/'manifest.json',manifest)
    for rel,h in manifest['source_sha256'].items():assert sha(ROOT/rel)==h,rel
    for rel,h in manifest['runtime_sha256'].items():assert sha(batch/rel)==h,rel
    print('BATCH '+str(batch),flush=True)
    if args.prepare_only:return
    completed=[];pending=[]
    for item in manifest['schedule']:
        directory=batch/(batch.name+'--'+item['arm'])/(item['task_id']+'-'+str(item['repetition']))
        if (directory/'result.json').exists():
            value=read(directory/'result.json');assert value.get('scoring_version')=='test-evidence-v4-per-case-calibrated';completed.append(value)
        else:
            assert not directory.exists(),'Preserve interrupted trial; never silently rerun'
            pending.append(item)
    manifest['runs']=completed;save(batch/'manifest.json',manifest)
    with runtime(manifest['runtime_paths']['adapter']),ThreadPoolExecutor(max_workers=2) as pool:
        futures={pool.submit(execute,item,manifest,config,model,batch):item for item in pending}
        for future in as_completed(futures):
            try:result=future.result()
            except Exception as error:
                save(batch/'INVALIDATED.json',dict(reason='harness_error',error_type=type(error).__name__,trial=futures[future]))
                for queued in futures:queued.cancel()
                raise
            manifest['runs'].append(result);save(batch/'manifest.json',manifest)
            print('PROGRESS',len(manifest['runs']),'/26',result['cohort'],result['arm'],result['task_id'],result['status'],flush=True)
    for rel,h in manifest['source_sha256'].items():assert sha(ROOT/rel)==h,rel
    for rel,h in manifest['runtime_sha256'].items():assert sha(batch/rel)==h,rel
    save(batch/'freeze-end-verification.json',dict(sources_unchanged=True,runtime_unchanged=True,completed=len(manifest['runs'])))
    summary(batch,manifest)


if __name__=='__main__':main()
