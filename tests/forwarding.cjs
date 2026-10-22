const { chromium, expect } = require('@playwright/test');
const { spawn } = require('node:child_process');
const http = require('node:http');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

(async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'optilens-forwarding-'));
  const backend = spawn('java', ['-jar', path.join(__dirname, '../target/optilens.jar')], {
    env: { ...process.env, OPTILENS_PORT: '0', OPTILENS_DATA_DIR: directory },
    stdio: ['ignore', 'pipe', 'inherit']
  });
  let proxy, browser;
  try {
    const backendPort = await new Promise((resolve, reject) => {
      let output = '';
      const timer = setTimeout(() => reject(new Error('Backend startup timed out')), 15000);
      backend.on('error', error => { clearTimeout(timer); reject(error); });
      backend.on('exit', code => { clearTimeout(timer); reject(new Error(`Backend exited: ${code}`)); });
      backend.stdout.on('data', chunk => {
        output += chunk;
        const match = output.match(/http:\/\/127\.0\.0\.1:(\d+)/);
        if (match) { clearTimeout(timer); resolve(Number(match[1])); }
      });
    });
    let rewriteHost = false;
    proxy = http.createServer((request, response) => {
      const headers = { ...request.headers };
      if (rewriteHost) headers.host = `127.0.0.1:${backendPort}`;
      const upstream = http.request({ hostname: '127.0.0.1', port: backendPort,
        path: request.url, method: request.method, headers }, result => {
        response.writeHead(result.statusCode, result.headers);
        result.pipe(response);
      });
      upstream.on('error', error => { response.writeHead(502); response.end(error.message); });
      request.pipe(upstream);
    });
    await new Promise((resolve, reject) => { proxy.on('error', reject); proxy.listen(0, '127.0.0.1', resolve); });
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto(`http://localhost:${proxy.address().port}`);
    await expect(page.locator('#run')).toBeEnabled();
    for (const rewriting of [false, true]) {
      rewriteHost = rewriting;
      await page.click('#run');
      await expect(page.locator('#status')).toHaveText(/^Completed/, { timeout: 40000 });
      await expect(page.locator('#after .plan-node').first()).toBeVisible();
    }
    console.log('Forwarding checks passed: page, catalog and real SQL submissions work with preserved or rewritten Host and a different browser port.');
  } finally {
    if (browser) await browser.close();
    if (proxy) { proxy.closeAllConnections(); await new Promise(resolve => proxy.close(resolve)); }
    if (backend.exitCode === null) {
      const exited = new Promise(resolve => backend.once('exit', resolve));
      backend.kill('SIGTERM');
      const timer = setTimeout(() => backend.kill('SIGKILL'), 12000);
      await exited; clearTimeout(timer);
    }
    await fs.rm(directory, { recursive: true, force: true });
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
