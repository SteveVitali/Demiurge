const http = require('http');
const port = process.env.PORT || 3456;
const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end('{"status":"ok"}');
  } else {
    res.writeHead(200, {'Content-Type': 'text/plain'});
    res.end('Hello from simple-node-http');
  }
});
server.listen(port, () => {
  console.log(`Server listening on port ${port}`);
});
