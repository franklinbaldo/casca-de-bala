// Validate the distributed source manifest; no network, build or device access.
import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';
const root=path.resolve(import.meta.dirname,'..');
const manifest=JSON.parse(fs.readFileSync(path.join(root,'SOURCE-MANIFEST.json')));
let count=0;
for(const file of manifest.files){
 if(!file.path||path.isAbsolute(file.path)||file.path.split('/').includes('..'))throw Error('Unsafe path');
 const resolved=path.join(root,file.path);const stat=fs.lstatSync(resolved);
 if(!stat.isFile()||stat.isSymbolicLink())throw Error('Not regular source: '+file.path);
 const b=fs.readFileSync(resolved);if(b.length!==file.bytes||crypto.createHash('sha256').update(b).digest('hex')!==file.sha256)throw Error('Hash mismatch: '+file.path);
 const text=b.toString('utf8');
 if(/libfile_|Sentinel_|file_00000000|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:[a-f0-9]{2}:){5}[a-f0-9]{2}\b/i.test(text)&&file.path!== 'scripts/check-source.mjs')throw Error('Review content: '+file.path);
 if(/\.(?:apk|bin|zip|sqlite|db|jks|keystore|so|class|pyc)$/i.test(file.path))throw Error('Artifact in source: '+file.path);
 count++;
}
console.log('PASS '+count+' source files match manifest; no private identifiers/artifacts detected by limited checks');
