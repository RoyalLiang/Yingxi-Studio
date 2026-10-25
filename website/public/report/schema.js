export const CONSENT_VERSION = '2026-10-26';
export const REPOSITORY = 'https://github.com/RoyalLiang/Yingxi-Studio';
const counts = new Set(`httpPhaseMs urlRequests rangeRequests sourceReadBytes readParallelism diskCacheReadBytes urlResolveMs responseWaitMs bodyReadMs lastHttpStatus sourceBodyAverageBytesPerSecond signedTrafficLimitBitsPerSecond versionCode sdk bufferingWaitMs suppression positionMs bufferedMs ioWaitingMs sinceReadMs ioFailures bytes readCalls maxReadMs readTimeMs droppedFrames audioRenderedBuffers sdrDecoderDroppedFrames sdrPollMs sdrPollMainMs encodedFrameRate videoSkippedOutputs encodedWidth encodedHeight openCalls openTimeMs firstFrameMs rebuffers decoderInitMs videoInputBuffers videoRenderedBuffers videoSkippedInputs videoCodecErrors videoDecoderReleases videoWidth videoHeight`.split(' '));
const flags = new Set(['downloadRightsNotice','playRequested','isPlaying','videoRendererEnabled']);
const codec = /^(?:mpv\.|c2\.|OMX\.|ffmpeg|libavcodec)[A-Za-z0-9._+-]{0,150}$/i;
const simple = /^[\p{L}\p{N}][\p{L}\p{N} ._()+-]{0,79}$/u;
const token = /^[A-Z0-9_:-]{1,80}$/;
const videoCodecs = /^(?:(?:dvhe|dvh1|hvc1|hev1|avc1|avc3|av01|vp09)(?:\.[0-9a-flh]{1,8}){1,5})(?:,\s*(?:(?:dvhe|dvh1|hvc1|hev1|avc1|avc3|av01|vp09)(?:\.[0-9a-flh]{1,8}){1,5}))*$/i;
const mimes = new Set(['video/avc','video/hevc','video/dolby-vision','video/av01','video/x-vnd.on2.vp9','video/x-vnd.on2.vp8','video/mp4v-es','video/mpeg2','video/3gpp']);
function number(value, min=0, max=Number.MAX_SAFE_INTEGER) { return typeof value==='number'&&Number.isFinite(value)&&value>=min&&value<=max ? value : null; }
export function sanitizeReport(input) {
  if(!input||typeof input!=='object'||Array.isArray(input)||input.report!=='yingxi-playback-v1')throw Error('请粘贴映汐生成的播放检修 JSON。');
  if(typeof input.version!=='string'||!/^\d{1,3}\.\d{1,3}\.\d{1,3}(?:[-+][A-Za-z0-9.-]{1,40})?$/.test(input.version))throw Error('报告中的应用版本无效。');
  const data={report:'yingxi-playback-v1',version:input.version};
  for(const key of counts)if(Object.hasOwn(input,key))data[key]=number(input[key]);
  for(const key of flags)if(Object.hasOwn(input,key))data[key]=typeof input[key]==='boolean'?input[key]:null;
  for(const key of ['manufacturer','model','android'])if(Object.hasOwn(input,key))data[key]=typeof input[key]==='string'&&simple.test(input[key])?input[key]:null;
  for(const key of ['state','ioOperation','httpPhase','lastIoFailure','playerError'])if(Object.hasOwn(input,key))data[key]=typeof input[key]==='string'&&token.test(input[key])?input[key]:null;
  if(Object.hasOwn(input,'source'))data.source=['SFTP','SMB','WEBDAV','ALIYUN','LOCAL'].includes(input.source)?input.source:null;
  for(const key of ['decoder','audioDecoder'])if(Object.hasOwn(input,key))data[key]=typeof input[key]==='string'&&codec.test(input[key])?input[key]:null;
  if(Object.hasOwn(input,'videoCodecs'))data.videoCodecs=typeof input.videoCodecs==='string'&&input.videoCodecs.length<=80&&videoCodecs.test(input.videoCodecs)?input.videoCodecs:null;
  if(Object.hasOwn(input,'videoMime'))data.videoMime=mimes.has(input.videoMime)?input.videoMime:null;
  if(Object.hasOwn(input,'playbackSpeed'))data.playbackSpeed=number(input.playbackSpeed,.1,16);
  if(Object.hasOwn(input,'sdrAvSyncMs'))data.sdrAvSyncMs=number(input.sdrAvSyncMs,-600000,600000);
  if(Array.isArray(input.nativeDolbyVisionDecoders))data.nativeDolbyVisionDecoders=input.nativeDolbyVisionDecoders.slice(0,16).filter(x=>x&&typeof x.name==='string'&&codec.test(x.name)).map(x=>({name:x.name,profileLevels:Array.isArray(x.profileLevels)?x.profileLevels.slice(0,32).map(y=>({profile:number(y?.profile),level:number(y?.level)})):[]}));
  const removed=Object.keys(input).filter(key=>!Object.hasOwn(data,key));
  return {data,removed};
}
