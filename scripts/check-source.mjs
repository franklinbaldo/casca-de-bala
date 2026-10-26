// Guard tracked files against build artifacts and private identifiers; no network.
import cp from 'node:child_process';import fs from 'node:fs';import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..');
const files=cp.execFileSync('git',['ls-files','-z'],{cwd:root,encoding:'utf8'}).split('\0').filter(Boolean);
const allowedBinary=new Set(['android-app/gradle/wrapper/gradle-wrapper.jar']);
let count=0;
for(const file of files){
 const resolved=path.join(root,file);const stat=fs.lstatSync(resolved);
 if(!stat.isFile()||stat.isSymbolicLink())throw Error('Not regular source: '+file);
 const text=fs.readFileSync(resolved).toString('utf8');
 if(/libfile_|Sentinel_|file_00000000|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:[a-f0-9]{2}:){5}[a-f0-9]{2}\b/i.test(text)&&file!=='scripts/check-source.mjs')throw Error('Review content: '+file);
 if(/\.(?:apk|bin|zip|sqlite|db|jks|keystore|p12|so|class|pyc|jar)$/i.test(file)&&!allowedBinary.has(file))throw Error('Artifact in source: '+file);
 count++;
}
console.log('PASS '+count+' tracked files; no private identifiers/artifacts detected by limited checks');
