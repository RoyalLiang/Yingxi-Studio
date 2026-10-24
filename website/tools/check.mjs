import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const root=fileURLToPath(new URL('../public/',import.meta.url));
const files=[];const errors=[];
function walk(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const file=path.join(dir,entry.name);if(entry.isSymbolicLink())errors.push(`Unexpected symlink: ${file}`);else if(entry.isDirectory())walk(file);else files.push(file);}}
walk(root);
for(const file of files){
  const relative=path.relative(root,file);
  if(relative.split(path.sep).some(part=>part.startsWith('._')||['.DS_Store','__MACOSX','.AppleDouble'].includes(part)))errors.push(`Forbidden metadata: ${relative}`);
  if(fs.statSync(file).size>25*1024*1024)errors.push(`Cloudflare per-file limit: ${relative}`);
  if(fs.readFileSync(file).subarray(0,4).equals(Buffer.from('00051607','hex')))errors.push(`AppleDouble content: ${relative}`);
  if(!/\.(html|css|js)$/.test(file))continue;
  const source=fs.readFileSync(file,'utf8');
  const refs=[...source.matchAll(/(?:src|href|poster)=["']([^"']+)["']|url\(["']?([^)\x22' ]+)["']?\)/g)].map(match=>match[1]||match[2]);
  for(const ref of refs){
    if(/^(?:https?:|data:|mailto:)/.test(ref))continue;
    const url=new URL(ref,'https://preview.invalid/'+relative.split(path.sep).join('/'));
    let target=path.resolve(root,'.'+decodeURIComponent(url.pathname));
    if(target!==path.resolve(root)&&!target.startsWith(path.resolve(root)+path.sep)){errors.push(`Escaping reference: ${ref}`);continue;}
    if(fs.existsSync(target)&&fs.statSync(target).isDirectory())target=path.join(target,'index.html');
    if(!fs.existsSync(target)){errors.push(`Missing reference ${relative} -> ${ref}`);continue;}
    if(url.hash&&target.endsWith('.html')){const id=decodeURIComponent(url.hash.slice(1));const html=fs.readFileSync(target,'utf8');if(!html.includes(`id="${id}"`)&&!html.includes(`id='${id}'`))errors.push(`Missing anchor ${relative} -> ${ref}`);}
  }
  if(file.endsWith('.html')){
    if((source.match(/<h1\b/g)||[]).length!==1)errors.push(`One h1 required: ${relative}`);
    if(!source.includes('name="viewport"'))errors.push(`Missing viewport: ${relative}`);
  }
}
const letters=JSON.parse(fs.readFileSync(path.join(root,'assets/letters.json'),'utf8'));
for(const key of ['axi','chideng','mitu'])if(!letters[key]?.letter||!letters[key]?.record)errors.push(`Missing canonical letter: ${key}`);
const atlasArg=process.argv.indexOf('--atlas');
if(atlasArg!==-1){const atlas=JSON.parse(fs.readFileSync(process.argv[atlasArg+1],'utf8'));for(const [key,letter] of Object.entries(letters)){const record=atlas.dailyLines.find(line=>line.id===letter.record&&line.author===key);if(record?.text.zh!==letter.letter||record?.text.en!==letter.english)errors.push(`Atlas letter differs: ${key}`);}}
const world=fs.readFileSync(path.join(root,'world/index.html'),'utf8');
for(const id of ['eras','atlas','life','residents','boundaries','glossary','place-station','place-salt','place-archive','place-sea','place-beacon','place-ideal','resident-axi','resident-chideng','resident-mitu'])if(!world.includes(`id="${id}"`))errors.push(`Missing world archive section: ${id}`);
const captures=JSON.parse(fs.readFileSync(path.join(root,'assets/screenshots.json'),'utf8'));
for(const asset of captures.assets){const file=path.join(root,'assets',asset.file);if(!fs.existsSync(file))errors.push(`Missing capture ${asset.file}`);else if(createHash('sha256').update(fs.readFileSync(file)).digest('hex')!==asset.sha256)errors.push(`Capture checksum differs: ${asset.file}`);}
if(!fs.readFileSync(path.join(root,'index.html'),'utf8').includes(captures.release.tag))errors.push('Download version differs from screenshot release');
if(errors.length){console.error(errors.join('\n'));process.exit(1);}
console.log(`Website checked: ${files.length} files, local links/anchors, 3 canonical letters, ${captures.assets.length} release-bound media assets and Cloudflare size limits.`);
