// Attach DevTools to the instrumented, unmodified Android WebView. No proxy,
// request interception, UA override, cookies, or response substitution.
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
const output = process.argv[2];
const deviceDir = '/sdcard/Android/data/com.mylo.browser/files/test-artifacts/settings-search';
const report = { verified: false, requests: [], responses: [], failures: [], exceptions: [] };
const adb = (...args) => execFileSync('adb', args, { timeout: 3500, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
let socket;
let sequence = 0;
const pending = new Map();
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`${method} timed out`)); }, 4000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
function receive({ data }) {
  const message = JSON.parse(data);
  if (message.id) {
    const promise = pending.get(message.id);
    if (promise) { clearTimeout(promise.timer); pending.delete(message.id); message.error ? promise.reject(new Error(message.error.message)) : promise.resolve(message.result); }
    return;
  }
  const p = message.params;
  if (message.method === 'Network.requestWillBeSent') report.requests.push({
    id: p.requestId, url: p.request.url, method: p.request.method, type: p.type,
    redirect: p.redirectResponse ? { url: p.redirectResponse.url, status: p.redirectResponse.status } : null,
  });
  if (message.method === 'Network.responseReceived') report.responses.push({
    id: p.requestId, url: p.response.url, status: p.response.status, type: p.type,
    protocol: p.response.protocol, mimeType: p.response.mimeType,
    fromDiskCache: p.response.fromDiskCache, fromServiceWorker: p.response.fromServiceWorker,
  });
  if (message.method === 'Network.loadingFailed') report.failures.push({ id: p.requestId, error: p.errorText, type: p.type, canceled: p.canceled, blockedReason: p.blockedReason });
  if (message.method === 'Runtime.exceptionThrown') report.exceptions.push({ text: p.exceptionDetails.text, description: p.exceptionDetails.exception?.description });
}
const documentSnapshot = async () => (await call('Runtime.evaluate', {
  expression: `JSON.stringify({url:location.href,title:document.title,body:document.body?.innerText.slice(0,6000),
    userAgent:navigator.userAgent,cookieNames:document.cookie.split(';').map(x=>x.split('=')[0].trim()).filter(Boolean),
    navigation:performance.getEntriesByType('navigation').map(n=>({url:n.name,responseStatus:n.responseStatus,redirectCount:n.redirectCount,protocol:n.nextHopProtocol}))})`,
  returnByValue: true,
})).result?.value;
try {
  const deadline = Date.now() + 65000;
  let target;
  while (!target && Date.now() < deadline) {
    try {
      const pid = adb('shell', 'pidof', 'com.mylo.browser').split(' ')[0];
      if (pid) {
        adb('forward', 'tcp:9222', `localabstract:webview_devtools_remote_${pid}`);
        const response = await fetch('http://127.0.0.1:9222/json/list', { signal: AbortSignal.timeout(1500) });
        target = (await response.json()).find(page => page.type === 'page' && page.url.includes('search.yahoo.com'));
      }
    } catch { /* WebView is not created until the Home query is submitted. */ }
    if (!target) await pause(250);
  }
  if (!target?.webSocketDebuggerUrl) throw new Error('The Yahoo WebView debug target was unavailable within the bounded observation.');
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('DevTools connection timed out')), 4000);
    socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
    socket.addEventListener('error', error => { clearTimeout(timer); reject(error); }, { once: true });
  });
  socket.addEventListener('message', receive);
  await call('Network.enable');
  await call('Runtime.enable');
  await call('Page.enable');
  let mode = '';
  while (!mode && Date.now() < deadline) {
    try { mode = adb('shell', 'cat', `${deviceDir}/yahoo-network-ready`); } catch { await pause(250); }
  }
  if (!mode) throw new Error('The native Yahoo baseline did not finish. No reload attempted.');
  report.baseline = JSON.parse(await documentSnapshot());
  report.mode = mode;
  if (mode === 'reload-once') await call('Page.reload');
  await pause(8000);
  report.afterObservation = JSON.parse(await documentSnapshot());
  report.verified = true;
} catch (error) {
  report.error = String(error);
} finally {
  socket?.close();
  for (const { timer } of pending.values()) clearTimeout(timer);
  writeFileSync(output, JSON.stringify(report, null, 2));
  try { adb('shell', 'touch', `${deviceDir}/yahoo-network-complete`); } catch {}
  try { adb('forward', '--remove', 'tcp:9222'); } catch {}
}
