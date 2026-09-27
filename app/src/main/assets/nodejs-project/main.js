const http = require('http');

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({
    message: '這是 App 內建 Node.js 引擎回應的，不是靜態檔案',
    time: new Date().toISOString(),
    path: req.url,
    nodeVersion: process.version
  }));
});

server.listen(3000, '127.0.0.1', () => {
  console.log('Node 動態後端已啟動：http://127.0.0.1:3000');
});
