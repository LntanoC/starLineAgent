"""Convert a COMPLETE runner artifact directory plus the approved manifest to an import body.
Only creates a local JSON artifact. Send it to the API using an admin session over SSH tunnel.
"""
import argparse,json
from pathlib import Path

def prepare(run_dir,manifest,dataset_name='bad-case-regression'):
    run=json.loads((run_dir/'run.json').read_text(encoding='utf-8-sig'))
    if run.get('status')!='COMPLETE': raise ValueError('Run must be COMPLETE')
    definitions={x['caseId']:x for x in manifest}
    results=[]
    for line in (run_dir/'turns.jsonl').read_text(encoding='utf-8-sig').splitlines():
        if not line.strip(): continue
        row=json.loads(line)
        if row.get('phase','measure')=='warmup': continue
        case=definitions[row['caseId']]
        evaluation=row.get('quality',row.get('evaluation',row))
        results.append({'caseId':row['caseId'],'turnId':row['turnId'],'repetition':row['repetition'],
            'datasetTurnFingerprint':case['turnFingerprints'][row['turnId']],
            'status':row['status'],'scored':evaluation.get('scored',False),
            'passed':evaluation.get('passed'),'checks':evaluation.get('checks',[]),
            'failureClass':row.get('failureClass')})
    return {'runId':run['runId'],'datasetName':dataset_name,'runStatus':'COMPLETE','complete':True,
        'repetitions':run['repetitions'],'finishedAt':run['finishedAt'],'results':results}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--run-dir',type=Path,required=True)
    parser.add_argument('--manifest',type=Path,required=True);parser.add_argument('--dataset-name',default='bad-case-regression')
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    body=prepare(args.run_dir,json.loads(args.manifest.read_text(encoding='utf-8-sig')),args.dataset_name)
    args.output.write_text(json.dumps(body,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Prepared %d measured results'%len(body['results']))
