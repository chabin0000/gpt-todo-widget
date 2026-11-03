// Download an official, integrity-checked OpenAI runtime; never copy desktop login files.
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const {pipeline}=require('stream/promises');const {Readable}=require('stream');const {execFileSync}=require('child_process');
const root=path.resolve(__dirname,'..');const version='0.155.1';
(async()=>{
 const arch=process.argv[2]==='x86_64'?'x64':'arm64';const abi=arch==='arm64'?'arm64-v8a':'x86_64';
 const scratch=path.join(root,'build','runtime-download',arch);fs.mkdirSync(scratch,{recursive:true});
 const metadata=await fetch('https://registry.npmjs.org/@openai/codex/'+version+'-linux-'+arch).then(r=>{if(!r.ok)throw Error(r.status);return r.json()});
 const archive=path.join(scratch,'runtime.tgz');const response=await fetch(metadata.dist.tarball);if(!response.ok)throw Error(response.status);
 await pipeline(Readable.fromWeb(response.body),fs.createWriteStream(archive));
 const bytes=fs.readFileSync(archive);const integrity='sha512-'+crypto.createHash('sha512').update(bytes).digest('base64');
 if(integrity!==metadata.dist.integrity)throw Error('Official package integrity mismatch');
 execFileSync('tar',['-xzf',archive,'-C',scratch],{stdio:'inherit',windowsHide:true});
 const triple=(arch==='arm64'?'aarch64':'x86_64')+'-unknown-linux-musl';
 const binary=path.join(scratch,'package','vendor',triple,'bin','codex');const destination=path.join(root,'app','src','main','jniLibs',abi);
 fs.mkdirSync(destination,{recursive:true});fs.copyFileSync(binary,path.join(destination,'libcodex_runtime.so'));
 const provenance={version:metadata.version,source:metadata.dist.tarball,packageIntegrity:integrity,binarySha256:crypto.createHash('sha256').update(fs.readFileSync(binary)).digest('hex')};
 fs.writeFileSync(path.join(scratch,'provenance.json'),JSON.stringify(provenance,null,2));
 console.log('Official runtime prepared for '+abi+'. Android compatibility must be tested separately.');
})().catch(error=>{console.error(error);process.exitCode=1});
