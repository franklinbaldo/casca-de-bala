// Real Gradle/AGP verification, offline. No APK task, keytool, network or device.
import fs from 'node:fs';import path from 'node:path';import os from 'node:os';
import crypto from 'node:crypto';import cp from 'node:child_process';import assert from 'node:assert/strict';
const root=path.resolve(import.meta.dirname,'../android-app');
const script=fs.readFileSync(path.join(root,'build-apk.sh'),'utf8');
assert(!script.includes('--write-verification-metadata'),'Normal build must not write accepted checksums');
assert.equal((script.match(/--dependency-verification strict/g)||[]).length,3,'Preflight and both builds require strict verification');
assert(script.indexOf('test -s')<script.indexOf('keytool'),'Metadata must exist before any signing action');
const gradle=process.env.GRADLE_BIN||path.join(root,'gradlew');
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'b7-gradle-verification-'));
const digest=b=>crypto.createHash('sha256').update(b).digest('hex');
const metadataPath=path.join(root,'gradle/verification-metadata.xml');
const original=fs.readFileSync(metadataPath);const before=digest(original);
try{
 for(const file of ['build.gradle','settings.gradle','gradle.properties','app/build.gradle']){
  const target=path.join(temporary,file);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(path.join(root,file),target);
 }
 const target=path.join(temporary,'gradle/verification-metadata.xml');fs.mkdirSync(path.dirname(target),{recursive:true});
 const block=/<artifact name="gradle-8\.9\.2\.jar">[\s\S]*?<\/artifact>/;
 const xml=original.toString('utf8');assert(block.test(xml),'Expected pinned AGP artifact');
 const cases=[['matching',xml,true],['mismatched',xml.replace(block,m=>m.replace(/value="[a-f0-9]{64}"/g,'value="'+'0'.repeat(64)+'"')),false],['missing-checksum',xml.replace(block,''),false]];
 for(const [name,contents,success] of cases){
  fs.writeFileSync(target,contents);const hash=digest(contents);
  const run=cp.spawnSync(gradle,['--offline','--no-daemon','--dependency-verification','strict','--project-dir',temporary,'help'],{encoding:'utf8',timeout:120000,maxBuffer:16000000});
  if(run.error)throw run.error;
  const output=run.stdout+run.stderr;
  assert.equal(run.status===0,success,name+': '+output.slice(-3000));
  if(!success){assert.match(output,/Dependency verification failed/i);assert.match(output,/gradle-8\.9\.2\.jar/);}
  assert.equal(digest(fs.readFileSync(target)),hash,'Gradle must not rewrite metadata');
  console.log('PASS '+name+': real Gradle offline '+(success?'accepted pinned input':'rejected input')+'; metadata unchanged');
 }
 assert.equal(digest(fs.readFileSync(metadataPath)),before,'Distributed metadata unchanged');
}finally{fs.rmSync(temporary,{recursive:true,force:true});}
