'use strict';
// CI only: actual Desktop Core/Store/Cipher and HTTPS gateway, all data synthetic.
// Pairing material stays in the parent's fresh RUNNER_TEMP directory, never stdout.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const net = require('node:net');
const [desktopRoot, scratch] = process.argv.slice(2);
let core, gateway, providerCalls = 0, closing = false, startupStage = 'ENVIRONMENT', failureCode;
const file = name => path.join(scratch, name);

function stop(code = 0) {
  if (closing) return;
  closing = true;
  try {
    const requests = core?.store.list('companion_request') || [];
    const calculatorRoundTrip = requests.length === 1 &&
      requests[0].response?.processing?.provider === 'deterministic' &&
      requests[0].response?.reply === '2+2 = 4';
    const idle = !!core && !!gateway && !core.requests.size && !gateway.active.size;
    gateway?.close();
    fs.rmSync(file('pair.json'), {force:true});
    fs.writeFileSync(file('fixture-result.json'), JSON.stringify({
      schemaVersion:1, actualDesktopCore:true, loopbackOnly:true,
      providerCalls, calculatorRoundTrip, idle,
      localModelInferenceExecuted:false, meetingTranscriptionExecuted:false,
      ...(failureCode?{failureCode}:{})
    }));
    core?.close();
  } catch { code = 1; }
  process.exit(code);
}

async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  if (port < 1024) throw Error('Port unavailable');
  return port;
}

(async () => {
  if (process.platform !== 'win32' || process.env.RUNNER_ENVIRONMENT !== 'github-hosted' ||
      process.env.RUNNER_OS !== 'Windows' || !/^24\./.test(process.versions.node)) throw Error('Hosted Windows Node 24 required');
  const tempRoot = fs.realpathSync(process.env.RUNNER_TEMP);
  const actualScratch = fs.realpathSync(scratch);
  if (path.dirname(actualScratch) !== tempRoot || !path.basename(actualScratch).startsWith('privateai-interop-')) throw Error('Invalid scratch directory');
  startupStage = 'CORE_LOAD';
  const {Cipher} = require(path.join(desktopRoot,'source/security.cjs'));
  const {Store} = require(path.join(desktopRoot,'source/database.cjs'));
  const {Core} = require(path.join(desktopRoot,'source/core.cjs'));
  const {CompanionGateway} = require(path.join(desktopRoot,'source/companion-gateway.cjs'));
  startupStage = 'STORE_OPEN';
  const root = file('synthetic-vault');
  fs.mkdirSync(root);
  const store = new Store(path.join(root,'privateai.db'),new Cipher(crypto.randomBytes(32)));
  // Fail an unexpected provider call; never manufacture a model answer.
  const deniedProvider = {models:async()=>{providerCalls++;throw Error('Unexpected provider call');},
    generate:async()=>{providerCalls++;throw Error('Unexpected provider call');}};
  startupStage = 'CORE_SETTINGS';
  core = new Core({root,store,client:deniedProvider,machine:'synthetic-hosted-interop'});
  core.saveSettings({provider:'local',processingMode:'local-only',allowCloud:false,
    workerPaused:false,workspace:file('synthetic-reports')});
  // This harness exercises transfer/storage, not optional speech/draft inference.
  clearInterval(core.speechWorkTimer);
  startupStage = 'HTTPS_START';
  gateway = new CompanionGateway(core);
  await gateway.configure({enabled:true,host:'127.0.0.1',port:await freePort()});
  startupStage = 'PAIRING_CREATE';
  const grants = {chat:true,tasks:true,alerts:true,workflow:true,notifications:true};
  const pair = gateway.pair({name:'Synthetic Android interoperability',scopes:grants});
  const invitation = gateway.invite({name:'Synthetic Android bootstrap',scopes:grants});
  startupStage = 'READY_WRITE';
  fs.writeFileSync(file('pair.json'),JSON.stringify({pairUri:pair.pairingUrl,
    bootstrapUri:invitation.pairingUrl,prompt:'/calc 2+2',expectedReplyContains:'4'}),{flag:'wx',mode:0o600});
  fs.writeFileSync(file('ready.tmp'),JSON.stringify({pid:process.pid,platform:process.platform,
    nodeVersion:process.versions.node,actualDesktopCore:true,loopbackOnly:true}));
  fs.renameSync(file('ready.tmp'),file('ready.json'));
  process.stdin.setEncoding('utf8');
  process.stdin.on('data',data=>stop(data.trim()==='stop'?0:1));
  process.stdin.on('end',()=>stop(1));
  setTimeout(()=>stop(1),15*60*1000).unref();
})().catch(()=>{failureCode='STARTUP_'+startupStage;stop(1);});
process.on('SIGTERM',()=>stop(1));
process.on('SIGINT',()=>stop(1));
process.on('uncaughtException',()=>stop(1));
process.on('unhandledRejection',()=>stop(1));
