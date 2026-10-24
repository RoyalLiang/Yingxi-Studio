/** Deploy only the checked, committed website. Authentication stays with Wrangler. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync,spawnSync} from 'node:child_process';
const root=fileURLToPath(new URL('../',import.meta.url));
const args=process.argv.slice(2);const options=new Map();
for(let i=0;i<args.length;i+=2){if(!['--target','--project','--branch'].includes(args[i])||!args[i+1])throw Error('Use --target preview|production [--project name] [--branch preview-name]');options.set(args[i],args[i+1]);}
const target=options.get('--target')||'preview';
if(!['preview','production'].includes(target))throw Error('Target must be preview or production');
const config=JSON.parse(fs.readFileSync(path.join(root,'wrangler.jsonc'),'utf8'));
const project=options.get('--project')||process.env.CLOUDFLARE_PAGES_PROJECT_NAME||config.name;
if(!/^[a-z0-9][a-z0-9-]{0,57}$/.test(project))throw Error('Invalid Pages project name');
const branch=target==='production'?'main':(options.get('--branch')||'design-preview');
if(!/^[a-zA-Z0-9][a-zA-Z0-9/_-]{0,119}$/.test(branch)||target==='preview'&&branch==='main')throw Error('Invalid preview branch');
const check=spawnSync(process.execPath,[path.join(root,'tools/check.mjs')],{cwd:root,stdio:'inherit'});
if(check.status!==0)process.exit(check.status||1);
const git=argv=>execFileSync('git',argv,{cwd:root,encoding:'utf8'}).trim();
const commit=git(['rev-parse','HEAD']);
if(git(['status','--porcelain','--untracked-files=no']))throw Error('Commit tracked changes before deploying from this checkout');
if(git(['ls-files','--others','--','public']))throw Error('Remove or commit untracked public files before deploying');
if(target==='production'&&!fs.existsSync(path.join(root,'public/sitemap.xml')))throw Error('Set the verified public origin with tools/set-site-url.mjs, then commit before production deploy');
const result=spawnSync('wrangler',['pages','deploy',path.join(root,'public'),'--project-name',project,'--branch',branch,'--commit-hash',commit,'--commit-dirty=false'],{cwd:root,stdio:'inherit',env:{...process.env,WRANGLER_SEND_METRICS:'false'}});
if(result.error){console.error(result.error.message);process.exit(1);}if(result.signal)process.exit(1);process.exit(result.status??1);
