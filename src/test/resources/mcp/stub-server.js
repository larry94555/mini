#!/usr/bin/env node
// Minimal MCP stub server speaking newline-delimited JSON-RPC 2.0 over stdio.
// Serves: initialize, tools/list, tools/call, resources/list, resources/read, prompts/list, prompts/get.
// Used by McpLiveIntegrationTest to verify imini's MCP client end to end. Deliberately tiny.
const readline = require('readline');
const rl = readline.createInterface({ input: process.stdin });

function send(obj) { process.stdout.write(JSON.stringify(obj) + '\n'); }

rl.on('line', (line) => {
  line = line.trim();
  if (!line) return;
  let msg;
  try { msg = JSON.parse(line); } catch (e) { return; }
  const id = msg.id;
  const method = msg.method;
  if (method === 'notifications/initialized') return; // notification: no reply
  let result;
  switch (method) {
    case 'initialize':
      result = { protocolVersion: '2024-11-05', capabilities: {}, serverInfo: { name: 'stub', version: '1.0' } };
      break;
    case 'tools/list':
      result = { tools: [ { name: 'echo', description: 'Echo back text',
        inputSchema: { type: 'object', properties: { text: { type: 'string' } } } } ] };
      break;
    case 'tools/call': {
      const args = (msg.params && msg.params.arguments) || {};
      result = { content: [ { type: 'text', text: 'echo:' + (args.text || '') } ] };
      break;
    }
    case 'resources/list':
      result = { resources: [ { uri: 'mem://greeting', name: 'greeting' } ] };
      break;
    case 'resources/read': {
      const uri = (msg.params && msg.params.uri) || '';
      result = { contents: [ { uri, mimeType: 'text/plain', text: 'hello from resource ' + uri } ] };
      break;
    }
    case 'prompts/list':
      result = { prompts: [ { name: 'review', description: 'Review a file' } ] };
      break;
    case 'prompts/get': {
      const a = (msg.params && msg.params.arguments) || {};
      result = { messages: [ { role: 'user', content: { type: 'text',
        text: 'Please review ' + (a.file || 'the code') + ' carefully.' } } ] };
      break;
    }
    default:
      send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'method not found: ' + method } });
      return;
  }
  send({ jsonrpc: '2.0', id, result });
});
